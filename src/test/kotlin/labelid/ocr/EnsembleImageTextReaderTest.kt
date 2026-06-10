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
                StaticReader(ImageText(text = "RAPIDOCR TEXT", engine = "rapidocr-ppocrv5")),
                StaticReader(ImageText(text = "TESSERACT TEXT", engine = "tesseract-psm-11")),
            ),
        ).readImage(ImageInput(Path.of("label.png")))

        assertEquals(listOf("rapidocr-ppocrv5", "tesseract-psm-11"), result.sources().map { it.engine })
        assertTrue(result.text.contains("=== rapidocr-ppocrv5 ==="))
        assertTrue(result.text.contains("=== tesseract-psm-11 ==="))
    }

    @Test
    fun keepsSuccessfulSourcesWhenAnotherReaderFails() = runBlocking {
        val result = EnsembleImageTextReader(
            listOf(
                FailingReader("missing binary"),
                StaticReader(ImageText(text = "RAPIDOCR TEXT", engine = "rapidocr-ppocrv5")),
            ),
        ).readImage(ImageInput(Path.of("label.png")))

        assertEquals(listOf("rapidocr-ppocrv5"), result.sources().map { it.engine })
        assertTrue(result.diagnostics.any { it.contains("missing binary") })
    }

    @Test
    fun sequentialReadStopsBeforeLaterReadersWhenRequested() = runBlocking {
        val firstReader = CountingReader(ImageText(text = "FIRST TEXT", engine = "tesseract-psm-3"))
        val secondReader = CountingReader(ImageText(text = "SECOND TEXT", engine = "rapidocr-ppocrv5"))

        val result = EnsembleImageTextReader(
            listOf(firstReader, secondReader),
        ).readImageSequentially(ImageInput(Path.of("label.png"))) { imageText ->
            imageText.sources().any { it.engine == "tesseract-psm-3" }
        }

        assertEquals(listOf("tesseract-psm-3"), result.sources().map { it.engine })
        assertEquals(1, firstReader.calls)
        assertEquals(0, secondReader.calls)
    }

    @Test
    fun sequentialReadKeepsReadingUntilStopConditionPasses() = runBlocking {
        val firstReader = CountingReader(ImageText(text = "FIRST TEXT", engine = "tesseract-psm-3"))
        val secondReader = CountingReader(ImageText(text = "SECOND TEXT", engine = "rapidocr-ppocrv5"))

        val result = EnsembleImageTextReader(
            listOf(firstReader, secondReader),
        ).readImageSequentially(ImageInput(Path.of("label.png"))) { imageText ->
            imageText.sources().any { it.engine == "rapidocr-ppocrv5" }
        }

        assertEquals(listOf("tesseract-psm-3", "rapidocr-ppocrv5"), result.sources().map { it.engine })
        assertEquals(1, firstReader.calls)
        assertEquals(1, secondReader.calls)
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

    private class CountingReader(
        private val imageText: ImageText,
    ) : ImageTextReader {
        var calls: Int = 0
            private set

        override suspend fun readImage(image: ImageInput): ImageText {
            calls += 1
            return imageText
        }
    }

    private class FailingReader(
        private val message: String,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            throw ImageTextReadException(message)
    }
}
