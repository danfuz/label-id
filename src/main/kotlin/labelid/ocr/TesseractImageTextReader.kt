package labelid.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labelid.domain.ImageInput
import labelid.domain.ImageText
import java.time.Duration
import java.util.concurrent.TimeUnit

class TesseractImageTextReader(
    private val executable: String = "tesseract",
    private val language: String = "eng",
    private val pageSegmentationMode: Int = 6,
    private val timeout: Duration = Duration.ofSeconds(20),
) : ImageTextReader {
    override suspend fun readImage(image: ImageInput): ImageText = withContext(Dispatchers.IO) {
        val command = listOf(
            executable,
            image.path.toAbsolutePath().toString(),
            "stdout",
            "-l",
            language,
            "--psm",
            pageSegmentationMode.toString(),
            "-c",
            "preserve_interword_spaces=1",
        )

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        } catch (ex: Exception) {
            throw ImageTextReadException(
                "Could not start Tesseract. Install it and ensure '$executable' is on PATH.",
                ex,
            )
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ImageTextReadException("Tesseract timed out after ${timeout.seconds} seconds.")
        }

        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        if (process.exitValue() != 0) {
            throw ImageTextReadException(
                "Tesseract failed with exit code ${process.exitValue()}: ${stderr.ifBlank { "no error output" }}",
            )
        }

        ImageText(
            text = stdout,
            engine = "tesseract",
            confidence = null,
            diagnostics = stderr.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        )
    }
}
