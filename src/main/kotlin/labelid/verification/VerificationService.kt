package labelid.verification

import labelid.domain.CheckStatus
import labelid.domain.ExpectedField
import labelid.domain.FieldCheck
import labelid.domain.FieldKind
import labelid.domain.ImageInput
import labelid.domain.ImageTextSource
import labelid.domain.VerificationReport
import labelid.domain.VerificationStatus
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
        val imageText = reader.readImage(image)
        val textSources = imageText.sources()
        val checks = buildList {
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
            add(
                FieldCheck(
                    fieldName = "Government Warning Visual Style",
                    expected = "Bold heading, required type size, legible contrast",
                    observed = null,
                    status = CheckStatus.NOT_ASSESSED,
                    message = "Text-only OCR cannot reliably assess bold, type size, or contrast in v1.",
                ),
            )
        }

        return VerificationReport(
            status = summarize(checks),
            expected = expected,
            imageText = imageText,
            checks = checks,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private fun checkField(field: ExpectedField, textSources: List<ImageTextSource>): FieldCheck =
        bestSourceCheck(
            fieldName = field.name,
            expected = field.value,
            checks = textSources.map { source ->
                checkFieldInText(field, source.text).withSource(source.engine)
            },
            failMessage = "Expected text was not found by any OCR engine.",
        )

    private fun checkFieldInText(field: ExpectedField, actualText: String): FieldCheck =
        when (field.kind) {
            FieldKind.TEXT -> checkTextField(field, actualText)
            FieldKind.ALCOHOL_CONTENT -> checkAlcoholContent(field, actualText)
            FieldKind.NET_CONTENTS -> checkNetContents(field, actualText)
        }

    private fun checkTextField(field: ExpectedField, actualText: String): FieldCheck {
        if (TextNormalizer.containsLoose(actualText, field.value)) {
            return FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = field.value,
                status = CheckStatus.PASS,
                message = "Expected text appears on the label.",
            )
        }

        if (TextNormalizer.containsOrderedTokenWindow(actualText, field.value)) {
            return FieldCheck(
                fieldName = field.name,
                expected = field.value,
                observed = field.value,
                status = CheckStatus.PASS,
                message = "Expected words appear in OCR order with limited separation.",
            )
        }

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
            return checkTextField(field, actualText)
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
            return checkTextField(field, actualText)
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

    private fun checkGovernmentWarning(textSources: List<ImageTextSource>): FieldCheck =
        bestSourceCheck(
            fieldName = "Government Warning",
            expected = GovernmentWarning.TEXT,
            checks = textSources.map { source ->
                checkGovernmentWarningInText(source.text).withSource(source.engine)
            },
            failMessage = "Required government warning statement was not found by any OCR engine.",
        )

    private fun checkGovernmentWarningInText(actualText: String): FieldCheck {
        val hasHeading = GovernmentWarning.hasExactHeading(actualText)
        if (hasHeading && TextNormalizer.containsLoose(actualText, GovernmentWarning.TEXT)) {
            return FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = "Found",
                status = CheckStatus.PASS,
                message = "Required warning statement text appears on the label.",
            )
        }

        val foundAnchors = GovernmentWarning.ANCHOR_TOKENS.count { TextNormalizer.containsLoose(actualText, it) }
        val expectedTokens = TextNormalizer.tokens(GovernmentWarning.TEXT).distinct()
        val actualTokens = TextNormalizer.tokens(actualText).toSet()
        val coverage = expectedTokens.count { it in actualTokens }.toDouble() / expectedTokens.size
        val observed = buildList {
            add(if (hasHeading) "heading found" else "heading missing")
            add("$foundAnchors/${GovernmentWarning.ANCHOR_TOKENS.size} anchors")
            add("${(coverage * 100).toInt()}% token coverage")
        }.joinToString()
        val status = when {
            hasHeading && foundAnchors == GovernmentWarning.ANCHOR_TOKENS.size && coverage >= 0.70 -> CheckStatus.PASS
            hasHeading && foundAnchors >= GovernmentWarning.REVIEW_ANCHOR_THRESHOLD && coverage >= 0.50 -> CheckStatus.REVIEW
            else -> CheckStatus.FAIL
        }
        val message = when (status) {
            CheckStatus.PASS -> "Required warning statement is strongly supported by OCR tokens."
            CheckStatus.REVIEW -> "Government warning evidence is partial; review the label image."
            CheckStatus.FAIL -> "Required government warning statement was not found."
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
}
