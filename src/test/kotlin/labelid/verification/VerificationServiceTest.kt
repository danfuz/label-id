package labelid.verification

import kotlinx.coroutines.runBlocking
import labelid.domain.CheckStatus
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.VerificationStatus
import labelid.ocr.ImageTextReader
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerificationServiceTest {
    @Test
    fun passesWhenFieldsAndWarningArePresent() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                OLD TOM DISTILLERY
                STONE'S THROW
                750 mL
                45% Alc./Vol.
                ${GovernmentWarning.TEXT}
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = """
                Brand Name: Stone's Throw
                DBA/Trade Name: Old Tom Distillery
                Net Contents: 750 mL
                Alcohol Content: 45%
            """.trimIndent(),
        )

        assertEquals(VerificationStatus.PASS, report.status)
        assertTrue(report.checks.any { it.fieldName == "Government Warning" && it.status == CheckStatus.PASS })
    }

    @Test
    fun failsWhenExpectedTextIsMissing() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                OTHER BRAND
                750 mL
                45% Alc./Vol.
                ${GovernmentWarning.TEXT}
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: Stone's Throw",
        )

        assertEquals(VerificationStatus.FAIL, report.status)
        assertTrue(report.checks.any { it.fieldName == "Brand Name" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun reviewsWhenNoComparableApplicationFieldsAreParsed() = runBlocking {
        val service = VerificationService(StaticTextReader(GovernmentWarning.TEXT))

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "This is an unstructured note.",
        )

        assertEquals(VerificationStatus.REVIEW, report.status)
    }

    private class StaticTextReader(
        private val text: String,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            ImageText(text = text, engine = "test")
    }
}
