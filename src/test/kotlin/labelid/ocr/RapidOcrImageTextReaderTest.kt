package labelid.ocr

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RapidOcrImageTextReaderTest {
    @Test
    fun usesConfiguredRapidOcrPythonExecutableFirst() {
        assertEquals(
            "C:\\custom\\rapidocr-python.exe",
            RapidOcrImageTextReader.selectDefaultPythonExecutable(
                environment = mapOf(
                    "LABEL_ID_RAPIDOCR_PYTHON" to "C:\\custom\\rapidocr-python.exe",
                    "LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local",
                ),
                userHome = "C:\\Users\\dev",
                exists = { true },
            ),
        )
    }

    @Test
    fun fallsBackToStandardWindowsRapidOcrVenvPath() {
        val expected = Path.of("C:\\Users\\dev\\AppData\\Local", "label-id", "rapidocr-venv", "Scripts", "python.exe")

        assertEquals(
            expected.toString(),
            RapidOcrImageTextReader.selectDefaultPythonExecutable(
                environment = mapOf("LOCALAPPDATA" to "C:\\Users\\dev\\AppData\\Local"),
                userHome = "C:\\Users\\dev",
                exists = { it == expected },
            ),
        )
    }

    @Test
    fun embeddedRapidOcrScriptUsesPpOcrV5OnnxRuntime() {
        val script = RapidOcrImageTextReader.rapidOcrScriptForTesting()

        assertTrue(script.contains("EngineType.ONNXRUNTIME"))
        assertTrue(script.contains("OCRVersion.PPOCRV5"))
        assertTrue(script.contains("LangRec.EN"))
    }

    @Test
    fun extractsMarkedRapidOcrText() {
        val output = """
            __LABEL_ID_RAPIDOCR_TEXT__
            GOVERNMENT WARNING:
            45% ALC/VOL
            __LABEL_ID_RAPIDOCR_ELAPSE__
            1.4
        """.trimIndent()

        assertEquals(
            """
            GOVERNMENT WARNING:
            45% ALC/VOL
            """.trimIndent(),
            RapidOcrImageTextReader.extractRapidOcrText(output),
        )
    }
}
