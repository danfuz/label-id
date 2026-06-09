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
}
