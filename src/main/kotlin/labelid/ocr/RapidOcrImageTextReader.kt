package labelid.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labelid.domain.ImageInput
import labelid.domain.ImageText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class RapidOcrImageTextReader(
    private val pythonExecutable: String = defaultPythonExecutable(),
    private val timeout: Duration = defaultTimeout(),
) : ImageTextReader {
    override suspend fun readImage(image: ImageInput): ImageText = withContext(Dispatchers.IO) {
        val scriptPath = Files.createTempFile("label-id-rapidocr-", ".py")
        Files.writeString(scriptPath, rapidOcrScript)
        val command = listOf(
            pythonExecutable,
            scriptPath.toAbsolutePath().toString(),
            image.path.toAbsolutePath().toString(),
        )

        try {
            val process = try {
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            } catch (ex: Exception) {
                throw ImageTextReadException(
                    "Could not start RapidOCR using `$pythonExecutable`. Install RapidOCR and set " +
                        "LABEL_ID_RAPIDOCR_PYTHON to its Python executable.",
                    ex,
                )
            }

            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw ImageTextReadException("RapidOCR timed out after ${timeout.seconds} seconds.")
            }

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            if (process.exitValue() != 0) {
                throw ImageTextReadException(
                    "RapidOCR failed using `$pythonExecutable` with exit code ${process.exitValue()}: " +
                        stderr.ifBlank { stdout.ifBlank { "no output" } },
                )
            }

            val text = extractRapidOcrText(stdout.ifBlank { stderr })
            ImageText(
                text = text,
                engine = "rapidocr-ppocrv5",
                confidence = null,
                diagnostics = listOfNotNull(stderr.takeIf { stdout.isNotBlank() && it.isNotBlank() }),
            )
        } finally {
            Files.deleteIfExists(scriptPath)
        }
    }

    companion object {
        private const val TEXT_MARKER = "__LABEL_ID_RAPIDOCR_TEXT__"
        private const val ELAPSE_MARKER = "__LABEL_ID_RAPIDOCR_ELAPSE__"

        fun defaultPythonExecutable(): String {
            return selectDefaultPythonExecutable(
                environment = System.getenv(),
                userHome = System.getProperty("user.home"),
                exists = Files::isRegularFile,
            )
        }

        fun selectDefaultPythonExecutable(
            environment: Map<String, String>,
            userHome: String,
            exists: (Path) -> Boolean,
        ): String {
            environment["LABEL_ID_RAPIDOCR_PYTHON"]
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            return defaultPythonCandidates(environment, userHome)
                .firstOrNull(exists)
                ?.toString()
                ?: "python"
        }

        private fun defaultPythonCandidates(
            environment: Map<String, String>,
            userHome: String,
        ): List<Path> = buildList {
            environment["LOCALAPPDATA"]
                ?.takeIf { it.isNotBlank() }
                ?.let { localAppData ->
                    add(Path.of(localAppData, "label-id", "rapidocr-venv", "Scripts", "python.exe"))
                }

            add(Path.of(userHome, ".label-id", "rapidocr-venv", "Scripts", "python.exe"))
            add(Path.of(userHome, ".label-id", "rapidocr-venv", "bin", "python"))
            add(Path.of("/tmp", "label-id-rapidocr-venv", "bin", "python"))
        }

        fun defaultTimeout(): Duration =
            Duration.ofSeconds(
                System.getenv("LABEL_ID_RAPIDOCR_TIMEOUT_SECONDS")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: 20,
            )

        fun extractRapidOcrText(output: String): String =
            output.sectionAfterMarker(TEXT_MARKER, ELAPSE_MARKER) ?: output.trim()

        private val rapidOcrScript = """
            import sys
            from rapidocr import EngineType, LangDet, LangRec, ModelType, OCRVersion, RapidOCR

            TEXT_MARKER = "$TEXT_MARKER"
            ELAPSE_MARKER = "$ELAPSE_MARKER"

            image_path = sys.argv[1]
            engine = RapidOCR(
                params={
                    "Det.engine_type": EngineType.ONNXRUNTIME,
                    "Det.lang_type": LangDet.CH,
                    "Det.model_type": ModelType.MOBILE,
                    "Det.ocr_version": OCRVersion.PPOCRV5,
                    "Rec.engine_type": EngineType.ONNXRUNTIME,
                    "Rec.lang_type": LangRec.EN,
                    "Rec.model_type": ModelType.MOBILE,
                    "Rec.ocr_version": OCRVersion.PPOCRV5,
                    "Cls.engine_type": EngineType.ONNXRUNTIME,
                    "Cls.lang_type": LangDet.CH,
                    "Cls.model_type": ModelType.MOBILE,
                    "Cls.ocr_version": OCRVersion.PPOCRV5,
                }
            )
            result = engine(image_path, use_det=True, use_cls=True, use_rec=True)
            texts = [str(text).strip() for text in (getattr(result, "txts", None) or []) if str(text).strip()]

            print(TEXT_MARKER)
            print("\n".join(texts))
            print(ELAPSE_MARKER)
            print(getattr(result, "elapse", ""))
        """.trimIndent()

        fun rapidOcrScriptForTesting(): String = rapidOcrScript

        private fun String.sectionAfterMarker(
            startMarker: String,
            endMarker: String? = null,
        ): String? {
            val start = indexOf(startMarker)
            if (start < 0) return null

            val contentStart = start + startMarker.length
            val contentEnd = endMarker
                ?.let { indexOf(it, startIndex = contentStart) }
                ?.takeIf { it >= 0 }
                ?: length
            return substring(contentStart, contentEnd).trim()
        }
    }
}
