package labelid.verification

import labelid.domain.CheckStatus
import labelid.domain.ExpectedField
import labelid.domain.FieldCheck
import labelid.domain.FieldKind
import labelid.domain.ImageInput
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
                add(checkField(field, imageText.text))
            }

            add(checkGovernmentWarning(imageText.text))
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

    private fun checkField(field: ExpectedField, actualText: String): FieldCheck =
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

    private fun checkGovernmentWarning(actualText: String): FieldCheck {
        if (TextNormalizer.containsLoose(actualText, GovernmentWarning.TEXT)) {
            return FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = "Found",
                status = CheckStatus.PASS,
                message = "Required warning statement text appears on the label.",
            )
        }

        val expectedTokens = TextNormalizer.tokens(GovernmentWarning.TEXT)
        val actualTokens = TextNormalizer.tokens(actualText).toSet()
        val coverage = expectedTokens.count { it in actualTokens }.toDouble() / expectedTokens.size
        return if (coverage >= 0.9) {
            FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = "${(coverage * 100).toInt()}% token coverage",
                status = CheckStatus.REVIEW,
                message = "Most warning words were found, but OCR did not produce an exact normalized statement.",
            )
        } else {
            FieldCheck(
                fieldName = "Government Warning",
                expected = GovernmentWarning.TEXT,
                observed = "${(coverage * 100).toInt()}% token coverage",
                status = CheckStatus.FAIL,
                message = "Required government warning statement was not found.",
            )
        }
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
    const val TEXT =
        "GOVERNMENT WARNING: (1) According to the Surgeon General, women should not drink alcoholic beverages during pregnancy because of the risk of birth defects. (2) Consumption of alcoholic beverages impairs your ability to drive a car or operate machinery, and may cause health problems."
}
