package labelid.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labelid.domain.ImageInput
import labelid.domain.ImageText
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class PaddleOcrImageTextReader(
    private val pythonExecutable: String = System.getenv("LABEL_ID_PADDLEOCR_PYTHON") ?: "python",
    private val cacheHome: String = System.getenv("LABEL_ID_PADDLEOCR_CACHE_HOME")
        ?: Path.of(System.getProperty("user.home"), ".label-id", "paddleocr-cache").toString(),
    private val timeout: Duration = Duration.ofSeconds(45),
) : ImageTextReader {
    override suspend fun readImage(image: ImageInput): ImageText = withContext(Dispatchers.IO) {
        val command = listOf(
            pythonExecutable,
            "-c",
            paddleOcrScript,
            image.path.toAbsolutePath().toString(),
        )

        val process = try {
            ProcessBuilder(command)
                .apply { environment()["PADDLE_PDX_CACHE_HOME"] = cacheHome }
                .redirectErrorStream(false)
                .start()
        } catch (ex: Exception) {
            throw ImageTextReadException(
                "Could not start PaddleOCR. Install PaddleOCR and set LABEL_ID_PADDLEOCR_PYTHON to its Python executable.",
                ex,
            )
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ImageTextReadException("PaddleOCR timed out after ${timeout.seconds} seconds.")
        }

        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            throw ImageTextReadException(
                "PaddleOCR failed with exit code ${process.exitValue()}: ${stderr.ifBlank { stdout.ifBlank { "no output" } }}",
            )
        }

        val rawOutput = stdout.ifBlank { stderr }
        ImageText(
            text = extractPaddleText(rawOutput),
            engine = "paddleocr",
            confidence = null,
            diagnostics = listOfNotNull(stderr.takeIf { stdout.isNotBlank() && it.isNotBlank() }),
        )
    }

    companion object {
        private val recTextsPattern = Regex(
            pattern = """['"]rec_texts['"]\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val quotedStringPattern = Regex("""'([^'\\]*(?:\\.[^'\\]*)*)'|"((?:\\.|[^"\\])*)"""")

        fun extractPaddleText(output: String): String {
            val recTexts = recTextsPattern.find(output)?.groupValues?.get(1)
            if (recTexts.isNullOrBlank()) return output.trim()

            return quotedStringPattern
                .findAll(recTexts)
                .map { match ->
                    val singleQuotedValue = match.groupValues[1]
                    val doubleQuotedValue = match.groupValues[2]
                    singleQuotedValue.ifBlank { doubleQuotedValue }.unescapeQuotedString()
                }
                .joinToString(separator = "\n")
                .trim()
                .ifBlank { output.trim() }
        }

        private val paddleOcrScript = """
            import sys
            from paddleocr import PaddleOCR

            ocr = PaddleOCR(
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )
            for result in ocr.predict(sys.argv[1]):
                result.print()
        """.trimIndent()

        private fun String.unescapeQuotedString(): String =
            replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
    }
}
