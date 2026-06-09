package labelid.verification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextNormalizerTest {
    @Test
    fun normalizesApostrophesCaseAndWhitespace() {
        assertTrue(TextNormalizer.containsLoose("STONE'S   THROW", "Stone\u2019s Throw"))
    }

    @Test
    fun compactRemovesPunctuation() {
        assertEquals("45alcvol", TextNormalizer.compact("45% Alc./Vol."))
    }

    @Test
    fun computesSimilarity() {
        assertTrue(TextNormalizer.similarity("stonesthrow", "stonesthrov") > 0.9)
    }

    @Test
    fun matchesExpectedTokensInOrderWithLimitedSeparation() {
        val ocrText = """
            ABC
            STRAIGHT RYE WHISKY
            GOVERNMENT WARNING:
            According to the
            Surgeon
            General
            women should not drink alcoholic beverages
            SINGLE BARREL
        """.trimIndent()

        assertTrue(TextNormalizer.containsOrderedTokenWindow(ocrText, "ABC SINGLE BARREL"))
    }

    @Test
    fun matchesSingularAndPluralTokenFormsOnly() {
        val ocrText = """
            MAKERS
            PRIVATE SELECTION
            MARK
        """.trimIndent()

        assertTrue(TextNormalizer.containsOrderedTokenWindow(ocrText, "Maker Mark"))
    }

    @Test
    fun rejectsExpectedTokensOutOfOrder() {
        val ocrText = """
            SINGLE BARREL
            ABC
        """.trimIndent()

        assertFalse(TextNormalizer.containsOrderedTokenWindow(ocrText, "ABC SINGLE BARREL"))
    }

    @Test
    fun rejectsExpectedTokensThatAreTooFarApart() {
        val ocrText = """
            ABC
            filler one
            filler two
            filler three
            filler four
            filler five
            filler six
            filler seven
            filler eight
            SINGLE BARREL
        """.trimIndent()

        assertFalse(TextNormalizer.containsOrderedTokenWindow(ocrText, "ABC SINGLE BARREL"))
    }

    @Test
    fun rejectsSingleTokenExpectedText() {
        assertFalse(TextNormalizer.containsOrderedTokenWindow("ABC", "ABC"))
    }
}
