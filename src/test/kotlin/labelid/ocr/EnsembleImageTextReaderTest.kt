package labelid.ocr

import kotlinx.coroutines.runBlocking
import labelid.domain.ImageInput
import labelid.domain.ImageText
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnsembleImageTextReaderTest {
    @Test
    fun returnsSeparateSourcesFromSuccessfulReaders() = runBlocking {
        val result = EnsembleImageTextReader(
            listOf(
                StaticReader(ImageText(text = "PADDLE TEXT", engine = "paddleocr")),
                StaticReader(ImageText(text = "TESSERACT TEXT", engine = "tesseract-psm-11")),
            ),
        ).readImage(ImageInput(Path.of("label.png")))

        assertEquals(listOf("paddleocr", "tesseract-psm-11"), result.sources().map { it.engine })
        assertTrue(result.text.contains("=== paddleocr ==="))
        assertTrue(result.text.contains("=== tesseract-psm-11 ==="))
    }

    @Test
    fun keepsSuccessfulSourcesWhenAnotherReaderFails() = runBlocking {
        val result = EnsembleImageTextReader(
            listOf(
                FailingReader("missing binary"),
                StaticReader(ImageText(text = "PADDLE TEXT", engine = "paddleocr")),
            ),
        ).readImage(ImageInput(Path.of("label.png")))

        assertEquals(listOf("paddleocr"), result.sources().map { it.engine })
        assertTrue(result.diagnostics.any { it.contains("missing binary") })
    }

    @Test
    fun failsWhenNoReaderSucceeds() = runBlocking {
        assertFailsWith<ImageTextReadException> {
            EnsembleImageTextReader(
                listOf(
                    FailingReader("first failed"),
                    FailingReader("second failed"),
                ),
            ).readImage(ImageInput(Path.of("label.png")))
        }
    }

    private class StaticReader(
        private val imageText: ImageText,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            imageText
    }

    private class FailingReader(
        private val message: String,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            throw ImageTextReadException(message)
    }
}
