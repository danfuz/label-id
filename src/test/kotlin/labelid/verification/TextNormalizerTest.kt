package labelid.verification

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
