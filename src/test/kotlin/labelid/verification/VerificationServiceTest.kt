package labelid.verification

import kotlinx.coroutines.runBlocking
import labelid.domain.CheckStatus
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.ImageTextSource
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
        assertTrue(report.checks.none { it.fieldName == "Government Warning Visual Style" })
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
    fun passesFieldsUsingBestSourceFromMultipleOcrEngines() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "combined",
                    engine = "ensemble",
                    sourceTexts = listOf(
                        ImageTextSource(
                            text = """
                            750 mL
                            ${GovernmentWarning.TEXT}
                            """.trimIndent(),
                            engine = "paddleocr",
                        ),
                        ImageTextSource(
                            text = """
                            COPPER HILL DISTILLING
                            47% ALC/VOL
                            ${GovernmentWarning.TEXT}
                            """.trimIndent(),
                            engine = "tesseract-psm-11",
                        ),
                    ),
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = """
                Brand Name: COPPER HILL DISTILLING
                Net Contents: 750 mL
                Alcohol Content: 47%
            """.trimIndent(),
        )

        assertEquals(VerificationStatus.PASS, report.status)
        assertTrue(report.fieldCheck("Brand Name").observed.orEmpty().contains("tesseract-psm-11"))
        assertTrue(report.fieldCheck("Net Contents").observed.orEmpty().contains("paddleocr"))
    }

    @Test
    fun passesTextFieldUsingCompatibilityNormalizedCandidate() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                NORTH
                PIER
                ＳＰIＲIＴＳ
                ${GovernmentWarning.TEXT}
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: NORTH PIER SPIRITS",
        )

        assertEquals(CheckStatus.PASS, report.fieldCheck("Brand Name").status)
        assertTrue(report.fieldCheck("Brand Name").observed.orEmpty().contains("nfkc-normalized"))
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun passesWhenMultiWordTextFieldIsSplitButNearbyInOcrOrder() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                STRAIGHT RYE WHISKY
                GOVERNMENT WARNING:
                (1） According to the
                surgeon
                General
                women should not drink alcoholic beverages
                SINGLE BARREL
                during pregnancy
                because of the risk of
                birth defects.
                (2) Consumption of alcoholic beverages
                STRAIGHT RYE
                impairs yourabilityto drive acaroroperae
                WHISKY
                machinery,and may cause health problems.
                750
                ML
                45%ALC/VOL
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = """
                Brand Name: ABC
                Fanciful Name: ABC SINGLE BARREL
                Class/Type: STRAIGHT RYE WHISKY
                Net Contents: 750 mL
                Alcohol Content: 45%
            """.trimIndent(),
        )

        assertEquals(CheckStatus.PASS, report.fieldCheck("Fanciful Name").status)
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun failsWhenMultiWordTextFieldTokensAreTooFarApart() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                ${GovernmentWarning.TEXT}
                unrelated filler one
                unrelated filler two
                unrelated filler three
                unrelated filler four
                unrelated filler five
                unrelated filler six
                unrelated filler seven
                unrelated filler eight
                SINGLE BARREL
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Fanciful Name: ABC SINGLE BARREL",
        )

        assertEquals(CheckStatus.FAIL, report.fieldCheck("Fanciful Name").status)
        assertEquals(VerificationStatus.FAIL, report.status)
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

    @Test
    fun passesGovernmentWarningWhenNoisyOcrHasHeadingAnchorsAndCoverage() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                GOVERNMENT WARNING:
                (1) According to the surgeon General
                women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                (2) Consumption of alcoholic beverages
                impairs yourabilityto drive acaroroperae
                machinery,and may cause health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun passesGovernmentWarningWhenHeadingWordsAreSplitByOneOcrToken() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                GOVERNMENT
                According
                WARNING:
                to the Surgeon General, women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                Consumption of alcoholic beverages impairs your ability to drive a car or operate
                machinery, and may cause health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun failsGovernmentWarningWhenHeadingWordsAreSplitTooFarApart() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                GOVERNMENT
                According
                to
                WARNING:
                the Surgeon General, women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                Consumption of alcoholic beverages impairs your ability to drive a car or operate
                machinery, and may cause health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.FAIL, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.FAIL, report.status)
    }

    @Test
    fun reviewsGovernmentWarningWhenEvidenceIsPartial() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                GOVERNMENT WARNING:
                According to the Surgeon General women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                Consumption impairs health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.REVIEW, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.REVIEW, report.status)
    }

    @Test
    fun failsGovernmentWarningWhenRiskAndHealthAnchorsAreMissing() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                GOVERNMENT WARNING:
                According to the Surgeon General, women should not drink alcoholic beverages during pregnancy.
                Consumption of alcoholic beverages impairs your ability to drive a car or operate machinery.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.FAIL, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.FAIL, report.status)
    }

    @Test
    fun failsGovernmentWarningWhenHeadingIsMissing() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                According to the Surgeon General, women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                Consumption of alcoholic beverages impairs your ability to drive a car or operate
                machinery, and may cause health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.FAIL, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.FAIL, report.status)
    }

    @Test
    fun failsGovernmentWarningWhenHeadingIsNotUppercase() = runBlocking {
        val service = VerificationService(
            StaticTextReader(
                """
                ABC
                Government Warning:
                (1) According to the Surgeon General, women should not drink alcoholic beverages
                during pregnancy because of the risk of birth defects.
                (2) Consumption of alcoholic beverages impairs your ability to drive a car or operate
                machinery, and may cause health problems.
                """.trimIndent(),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.FAIL, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.FAIL, report.status)
    }

    @Test
    fun passesGovernmentWarningWhenAnchorEvidenceIsSplitAcrossOcrSources() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "combined",
                    engine = "ensemble",
                    sourceTexts = listOf(
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            (1) According to the Surgeon Genera,
                            women should not drink alcoholic beverages
                            during pregnancy because of the isk of
                            birth detects.
                            (2) Gonsumption of alcoholic beverages
                            impairs your ability to drive a car or operate
                            machinery, and may cause health problems.
                            """.trimIndent(),
                            engine = "tesseract-psm-3",
                        ),
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            (1) According to the Surgeon General,
                            women should not drink alcoholic beverages
                            during pregnancy because of the risk of
                            birth defects.
                            """.trimIndent(),
                            engine = "tesseract-psm-4",
                        ),
                    ),
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("9/9 anchors"))
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("tesseract-psm-3"))
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("tesseract-psm-4"))
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun doesNotPassGovernmentWarningWhenAnAnchorIsMissingFromEveryOcrSource() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "combined",
                    engine = "ensemble",
                    sourceTexts = listOf(
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            According to the Surgeon General women should not drink alcoholic beverages
                            during pregnancy because of the risk of birth.
                            Consumption of alcoholic beverages impairs your ability to drive a car or operate
                            machinery, and may cause health problems.
                            """.trimIndent(),
                            engine = "tesseract-psm-3",
                        ),
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            According to the Surgeon General
                            risk birth health problems impairs drive
                            """.trimIndent(),
                            engine = "tesseract-psm-4",
                        ),
                    ),
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertTrue(report.governmentWarningCheck().status != CheckStatus.PASS)
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("missing anchors: defects"))
    }

    @Test
    fun passesGovernmentWarningUsingBestEnsembleSource() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "combined",
                    engine = "ensemble",
                    sourceTexts = listOf(
                        ImageTextSource(
                            text = """
                            ABC
                            Government Warning:
                            According to the Surgeon General, women should not drink alcoholic beverages
                            during pregnancy because of the risk of birth defects.
                            Consumption of alcoholic beverages impairs your ability to drive a car or operate
                            machinery, and may cause health problems.
                            """.trimIndent(),
                            engine = "paddleocr",
                        ),
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            According to the Surgeon General, women should not drink alcoholic beverages
                            during pregnancy because of the risk of birth defects.
                            Consumption of alcoholic beverages impairs your ability to drive a car or operate
                            machinery, and may cause health problems.
                            """.trimIndent(),
                            engine = "tesseract-psm-11",
                        ),
                    ),
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("tesseract-psm-11"))
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun passesGovernmentWarningWhenOnlyTesseractSourceIsAvailable() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "ABC\n${GovernmentWarning.TEXT}",
                    engine = "tesseract-psm-11",
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertEquals(VerificationStatus.PASS, report.status)
    }

    @Test
    fun passesGovernmentWarningWhenTesseractHasTheBestCompleteWarning() = runBlocking {
        val service = VerificationService(
            StaticImageTextReader(
                ImageText(
                    text = "combined",
                    engine = "ensemble",
                    sourceTexts = listOf(
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            """.trimIndent(),
                            engine = "paddleocr",
                        ),
                        ImageTextSource(
                            text = """
                            ABC
                            GOVERNMENT WARNING:
                            According to the Surgeon General, women should not drink alcoholic beverages
                            during pregnancy because of the risk of birth defects.
                            Consumption of alcoholic beverages impairs your ability to drive a car or operate
                            machinery, and may cause health problems.
                            """.trimIndent(),
                            engine = "tesseract-psm-11",
                        ),
                    ),
                ),
            ),
        )

        val report = service.verify(
            image = ImageInput(Path.of("label.png")),
            rawApplicationText = "Brand Name: ABC",
        )

        assertEquals(CheckStatus.PASS, report.governmentWarningCheck().status)
        assertTrue(report.governmentWarningCheck().observed.orEmpty().contains("tesseract-psm-11"))
        assertEquals(VerificationStatus.PASS, report.status)
    }

    private fun labelid.domain.VerificationReport.governmentWarningCheck() =
        checks.first { it.fieldName == "Government Warning" }

    private fun labelid.domain.VerificationReport.fieldCheck(fieldName: String) =
        checks.first { it.fieldName == fieldName }

    private class StaticTextReader(
        private val text: String,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            ImageText(text = text, engine = "tesseract-psm-11")
    }

    private class StaticImageTextReader(
        private val imageText: ImageText,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            imageText
    }
}
