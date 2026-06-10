package labelid.verification

import labelid.domain.CheckStatus
import labelid.domain.ExpectedField
import labelid.domain.ExpectedLabelData
import labelid.domain.FieldCheck
import labelid.domain.FieldKind
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.ImageTextSource
import labelid.domain.VerificationReport
import labelid.domain.VerificationStatus
import labelid.ocr.EnsembleImageTextReader
import labelid.ocr.ImageTextReader
import labelid.parsing.ApplicationTextParser
import java.util.Locale

class VerificationService(
    private val reader: ImageTextReader,
    private val parser: ApplicationTextParser = ApplicationTextParser(),
) {
    suspend fun verify(image: ImageInput, rawApplicationText: String): VerificationReport {
        val startedAt = System.nanoTime()
        val expected = parser.parse(rawApplicationText)
        val imageText = readImageForVerification(image, expected)
        val checks = buildChecks(expected, imageText.sources())

        return VerificationReport(
            status = summarize(checks),
            expected = expected,
            imageText = imageText,
            checks = checks,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private suspend fun readImageForVerification(
        image: ImageInput,
        expected: ExpectedLabelData,
    ): ImageText =
        if (reader is EnsembleImageTextReader) {
            reader.readImageSequentially(image) { candidateImageText ->
                summarize(buildChecks(expected, candidateImageText.sources())) == VerificationStatus.PASS
            }
        } else {
            reader.readImage(image)
        }

    private fun buildChecks(
        expected: ExpectedLabelData,
        textSources: List<ImageTextSource>,
    ): List<FieldCheck> =
        buildList {
            if (!expected.hasComparableFields()) {
                add(
                    FieldCheck(
                        fieldName = "Application Text",
                        expected = "Known COLA fields",
                        observed = null,
                        status = CheckStatus.REVIEW,
                        message = "No comparable label fields were parsed from the pasted text.",
                    ),
                )
            }

            expected.comparableFields().forEach { field ->
                add(checkField(field, textSources))
            }

            add(checkGovernmentWarning(textSources))
        }

    private fun checkField(field: ExpectedField, textSources: List<ImageTextSource>): FieldCheck =
        bestSourceCheck(
            fieldName = field.name,
            expected = field.value,
            checks = textSources.flatMap { source ->
                checkFieldInText(field, source.text).map { candidate ->
                    candidate.withSource(source.engine)
                }
            },
            failMessage = "Expected text was not found by any OCR engine.",
        )

    private fun checkFieldInText(field: ExpectedField, actualText: String): List<CheckCandidate> =
        when (field.kind) {
            FieldKind.TEXT -> checkTextField(field, actualText)
            FieldKind.ALCOHOL_CONTENT -> listOf(checkAlcoholContent(field, actualText).withMode("numeric"))
            FieldKind.NET_CONTENTS -> listOf(checkNetContents(field, actualText).withMode("numeric"))
        }

    private fun checkTextField(field: ExpectedField, actualText: String): List<CheckCandidate> = buildList {
        add(checkRawTextField(field, actualText).withMode("raw"))
        add(checkNormalizedTextField(field, actualText).withMode("nfkc-normalized"))
        add(checkTokenWindowTextField(field, actualText).withMode("token-window"))
        add(checkSimilarityTextField(field, actualText).withMode("similarity"))
    }

    private fun checkRawTextField(field: ExpectedField, actualText: String): FieldCheck =
        if (TextNormalizer.containsRaw(actualText, field.value)) {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = field.value,
                status = CheckStatus.PASS,
                message = "Expected text appears on the label without normalization.",
            )
        } else {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = null,
                status = CheckStatus.FAIL,
                message = "Expected raw text was not found in the OCR output.",
            )
        }

    private fun checkNormalizedTextField(field: ExpectedField, actualText: String): FieldCheck {
        if (TextNormalizer.containsLoose(actualText, field.value)) {
            return FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = field.value,
                status = CheckStatus.PASS,
                message = "Expected text appears on the label after safe normalization.",
            )
        }

        return FieldCheck(
            fieldName = field.name,
            expected = field.value,
            observed = null,
            status = CheckStatus.FAIL,
            message = "Expected normalized text was not found in the OCR output.",
        )
    }

    private fun checkTokenWindowTextField(field: ExpectedField, actualText: String): FieldCheck {
        if (TextNormalizer.containsOrderedTokenWindow(actualText, field.value)) {
            return FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = field.value,
                status = CheckStatus.PASS,
                message = "Expected words appear in OCR order with limited separation.",
            )
        }

        return FieldCheck(
            fieldName = field.name,
            expected = field.value,
            observed = null,
            status = CheckStatus.FAIL,
            message = "Expected words were not found in OCR order with the required locality.",
        )
    }

    private fun checkSimilarityTextField(field: ExpectedField, actualText: String): FieldCheck {
        val bestSimilarity = TextNormalizer.bestCompactWindowSimilarity(field.value, actualText)
        return if (bestSimilarity >= 0.92) {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = null,
                status = CheckStatus.REVIEW,
                message = "A close OCR match was found (${(bestSimilarity * 100).toInt()}% similar).",
            )
        } else {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = null,
                status = CheckStatus.FAIL,
                message = "Expected text was not found in the OCR output.",
            )
        }
    }

    private fun checkAlcoholContent(field: ExpectedField, actualText: String): FieldCheck {
        val expected = AlcoholContentParser.parseExpected(field.value)
        if (expected == null) {
            return bestCandidate(checkTextField(field, actualText))
        }

        val observed = AlcoholContentParser.parseAll(actualText)
        val match = observed.firstOrNull { kotlin.math.abs(it - expected) <= 0.01 }
        return if (match != null) {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = "${trimNumber(match)}%",
                status = CheckStatus.PASS,
                message = "Alcohol content matches numerically.",
            )
        } else {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = observed.joinToString { "${trimNumber(it)}%" }.ifBlank { null },
                status = CheckStatus.FAIL,
                message = "Expected alcohol content was not found.",
            )
        }
    }

    private fun checkNetContents(field: ExpectedField, actualText: String): FieldCheck {
        val expected = NetContentsParser.parseExpected(field.value)
        if (expected == null) {
            return bestCandidate(checkTextField(field, actualText))
        }

        val observed = NetContentsParser.parseAll(actualText)
        val match = observed.firstOrNull { it.equivalentTo(expected) }
        return if (match != null) {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = "${trimNumber(match.milliliters)} mL",
                status = CheckStatus.PASS,
                message = "Net contents match numerically.",
            )
        } else {
            FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = observed.joinToString { "${trimNumber(it.milliliters)} mL" }.ifBlank { null },
                status = CheckStatus.FAIL,
                message = "Expected net contents were not found.",
            )
        }
    }

    private fun checkGovernmentWarning(textSources: List<ImageTextSource>): FieldCheck {
        if (textSources.isEmpty()) {
            return FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = null,
                status = CheckStatus.FAIL,
                message = "Required government warning statement was not found by any OCR engine.",
            )
        }

        val headingSources = textSources.filter { GovernmentWarning.hasExactHeading(it.text) }
        val exactStatementSource = headingSources.firstOrNull { TextNormalizer.containsLoose(it.text, GovernmentWarning.TEXT) }
        if (exactStatementSource != null) {
            return FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = "Found [${exactStatementSource.engine}]",
                status = CheckStatus.PASS,
                message = "Required warning statement text appears on the label with an OCR-confirmed heading.",
            )
        }

        val hasHeading = headingSources.isNotEmpty()
        val actualAnchorTokens = textSources
            .flatMap { TextNormalizer.tokens(it.text) }
            .map(TextNormalizer::compact)
            .filter { it.isNotBlank() }
            .toSet()
        val foundAnchors = GovernmentWarning.ANCHOR_TOKENS.filter { anchor ->
            actualAnchorTokens.any { actualToken -> GovernmentWarning.matchesAnchor(anchor, actualToken) }
        }
        val expectedTokens = TextNormalizer.tokens(GovernmentWarning.TEXT).distinct()
        val actualTokens = textSources.flatMap { TextNormalizer.tokens(it.text) }.toSet()
        val coverage = expectedTokens.count { it in actualTokens }.toDouble() / expectedTokens.size
        val sourceNames = textSources.joinToString { it.engine }
        val observed = buildList {
            add(
                if (hasHeading) {
                    "heading found [${headingSources.joinToString { it.engine }}]"
                } else {
                    "heading missing"
                },
            )
            add("${foundAnchors.size}/${GovernmentWarning.ANCHOR_TOKENS.size} anchors")
            val missingAnchors = GovernmentWarning.ANCHOR_TOKENS - foundAnchors.toSet()
            if (missingAnchors.isNotEmpty()) {
                add("missing anchors: ${missingAnchors.joinToString()}")
            }
            add("${(coverage * 100).toInt()}% token coverage")
            add("OCR sources: $sourceNames")
        }.joinToString()
        val status = when {
            hasHeading && foundAnchors.size == GovernmentWarning.ANCHOR_TOKENS.size && coverage >= 0.70 -> CheckStatus.PASS
            hasHeading && foundAnchors.size >= GovernmentWarning.REVIEW_ANCHOR_THRESHOLD && coverage >= 0.50 -> CheckStatus.REVIEW
            else -> CheckStatus.FAIL
        }
        val message = when (status) {
            CheckStatus.PASS -> "Required warning statement is strongly supported by aggregate OCR tokens."
            CheckStatus.REVIEW -> "Government warning evidence is partial; review the label image."
            CheckStatus.FAIL -> "Required government warning statement was not found with an OCR-confirmed heading."
            CheckStatus.NOT_ASSESSED -> error("Government warning text check is always assessed.")
        }

        return FieldCheck(
            fieldName = "Government Warning",
            expected = GovernmentWarning.TEXT,
            observed = observed,
            status = status,
            message = message,
        )
    }

    private fun bestSourceCheck(
        fieldName: String,
        expected: String,
        checks: List<FieldCheck>,
        failMessage: String,
    ): FieldCheck {
        if (checks.isEmpty()) {
            return FieldCheck(
                fieldName = fieldName,
                expected = expected,
                observed = null,
                status = CheckStatus.FAIL,
                message = failMessage,
            )
        }

        val best = checks.minWith(compareBy<FieldCheck> { it.status.rank }.thenByDescending { it.observed?.length ?: 0 })
        return if (best.status == CheckStatus.FAIL && checks.size > 1) {
            best.copy(message = failMessage)
        } else {
            best
        }
    }

    private fun bestCandidate(candidates: List<CheckCandidate>): FieldCheck =
        candidates.map { it.check }
            .minWith(compareBy<FieldCheck> { it.status.rank }.thenByDescending { it.observed?.length ?: 0 })

    private fun FieldCheck.withMode(mode: String): CheckCandidate =
        CheckCandidate(check = this, mode = mode)

    private fun CheckCandidate.withSource(engine: String): FieldCheck {
        val sourceLabel = buildList {
            add(engine)
            mode?.let(::add)
        }.joinToString()
        return check.copy(
            observed = check.observed?.let { "$it [$sourceLabel]" },
            message = buildString {
                append(check.message)
                append(" OCR source: ")
                append(engine)
                mode?.let {
                    append(". Match mode: ")
                    append(it)
                }
                append(".")
            },
        )
    }

    private fun FieldCheck.withSource(engine: String): FieldCheck =
        copy(
            observed = observed?.let { "$it [$engine]" },
            message = "$message OCR source: $engine.",
        )

    private val CheckStatus.rank: Int
        get() = when (this) {
            CheckStatus.PASS -> 0
            CheckStatus.REVIEW -> 1
            CheckStatus.FAIL -> 2
            CheckStatus.NOT_ASSESSED -> 3
        }

    private fun summarize(checks: List<FieldCheck>): VerificationStatus =
        when {
            checks.any { it.status == CheckStatus.FAIL } -> VerificationStatus.FAIL
            checks.any { it.status == CheckStatus.REVIEW } -> VerificationStatus.REVIEW
            else -> VerificationStatus.PASS
        }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private data class CheckCandidate(
        val check: FieldCheck,
        val mode: String? = null,
    )
}

object GovernmentWarning {
    const val HEADING = "Government Warning"
    const val REQUIRED_HEADING = "GOVERNMENT WARNING:"
    const val TEXT =
        "GOVERNMENT WARNING: (1) According to the Surgeon General, women should not drink alcoholic beverages during pregnancy because of the risk of birth defects. (2) Consumption of alcoholic beverages impairs your ability to drive a car or operate machinery, and may cause health problems."
    val ANCHOR_TOKENS = listOf(
        "surgeon",
        "general",
        "impairs",
        "drive",
        "risk",
        "birth",
        "defects",
        "health",
        "problems",
    )
    val REVIEW_ANCHOR_THRESHOLD = (ANCHOR_TOKENS.size * 2 + 2) / 3
    private const val MAX_ANCHOR_EDIT_DISTANCE = 1

    fun hasExactHeading(actualText: String): Boolean {
        if (Regex("""\bGOVERNMENT\s+WARNING\s*:""").containsMatchIn(actualText)) {
            return true
        }

        val tokens = Regex("""[A-Za-z]+:?""")
            .findAll(actualText)
            .map { it.value }
            .toList()

        return tokens.withIndex().any { (index, token) ->
            token == "GOVERNMENT" &&
                tokens.drop(index + 1)
                    .take(MAX_INTERVENING_HEADING_TOKENS + 1)
                    .any { it == "WARNING:" }
        }
    }

    private const val MAX_INTERVENING_HEADING_TOKENS = 1

    fun matchesAnchor(anchor: String, actualToken: String): Boolean =
        boundedEditDistance(anchor, actualToken, MAX_ANCHOR_EDIT_DISTANCE) <= MAX_ANCHOR_EDIT_DISTANCE

    private fun boundedEditDistance(left: String, right: String, maxDistance: Int): Int {
        if (kotlin.math.abs(left.length - right.length) > maxDistance) return maxDistance + 1
        if (left == right) return 0

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            var rowMin = current[0]

            for (rightIndex in right.indices) {
                val insertCost = current[rightIndex] + 1
                val deleteCost = previous[rightIndex + 1] + 1
                val replaceCost = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
                val cost = minOf(insertCost, deleteCost, replaceCost)
                current[rightIndex + 1] = cost
                rowMin = minOf(rowMin, cost)
            }

            if (rowMin > maxDistance) return maxDistance + 1

            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }
}
