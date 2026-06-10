package labelid.ocr

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PaddleOcrImageTextReaderTest {
    @Test
    fun usesConfiguredPaddlePythonExecutableFirst() {
        assertEquals(
            "C:\\custom\\python.exe",
            PaddleOcrImageTextReader.selectDefaultPythonExecutable(
                environment = mapOf(
                    "LABEL_ID_PADDLEOCR_PYTHON" to "C:\\custom\\python.exe",
                    "LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local",
                ),
                userHome = "C:\\Users\\dev",
                exists = { true },
            ),
        )
    }

    @Test
    fun fallsBackToStandardWindowsPaddleVenvPath() {
        val expected = Path.of("C:\\Users\\dev\\AppData\\Local", "label-id", "paddleocr-venv", "Scripts", "python.exe")

        assertEquals(
            expected.toString(),
            PaddleOcrImageTextReader.selectDefaultPythonExecutable(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local"),
                userHome = "C:\\Users\\dev",
                exists = { it == expected },
            ),
        )
    }

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
