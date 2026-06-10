package labelid.ocr

import kotlin.test.Test
import kotlin.test.assertEquals

class PaddleOcrImageTextReaderTest {
    @Test
    fun extractsRecognizedTextLinesFromPaddleOutput() {
        val output = """
            {'res': {'rec_texts': ['RIVER', 'BEND', 'DISTILLING', 'GOVERNMENT', 'WARNING:', '45%ALC/VOL'], 'rec_scores': array([0.98])}}
        """.trimIndent()

        assertEquals(
            """
            RIVER
            BEND
            DISTILLING
            GOVERNMENT
            WARNING:
            45%ALC/VOL
            """.trimIndent(),
            PaddleOcrImageTextReader.extractPaddleText(output),
        )
    }

    @Test
    fun returnsTrimmedOutputWhenPaddleShapeIsUnknown() {
        assertEquals("plain text", PaddleOcrImageTextReader.extractPaddleText(" plain text "))
    }

    @Test
    fun extractsRecognitionAndSpatialSourcesFromMarkedOutput() {
        val output = """
            __LABEL_ID_PADDLEOCR_REC_TEXT__
            HILL
            COPPER
            DISTILLING
            __LABEL_ID_PADDLEOCR_SPATIAL_TEXT__
            COPPER HILL
            DISTILLING
        """.trimIndent()

        val sources = PaddleOcrImageTextReader.extractPaddleTextSources(output)

        assertEquals(listOf("paddleocr", "paddleocr-spatial"), sources.map { it.engine })
        assertEquals(
            """
            HILL
            COPPER
            DISTILLING
            """.trimIndent(),
            sources[0].text,
        )
        assertEquals(
            """
            COPPER HILL
            DISTILLING
            """.trimIndent(),
            sources[1].text,
        )
    }
}
