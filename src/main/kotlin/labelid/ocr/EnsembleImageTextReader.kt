package labelid.ocr

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.ImageTextSource

class EnsembleImageTextReader(
    private val readers: List<ImageTextReader> = defaultReaders(),
) : ImageTextReader {
    override suspend fun readImage(image: ImageInput): ImageText = supervisorScope {
        val attempts = readers
            .map { reader ->
                async {
                    runCatching {
                        reader.readImage(image)
                    }.fold(
                        onSuccess = { OcrAttempt.Success(it) },
                        onFailure = { OcrAttempt.Failure(reader.name, it.message ?: it::class.simpleName.orEmpty()) },
                    )
                }
            }
            .awaitAll()

        val successes = attempts.filterIsInstance<OcrAttempt.Success>().map { it.imageText }
        val failures = attempts.filterIsInstance<OcrAttempt.Failure>()
        if (successes.isEmpty()) {
            throw ImageTextReadException(
                failures.joinToString(
                    prefix = "No OCR engine succeeded. ",
                    separator = " ",
                ) { "${it.engine}: ${it.message}" },
            )
        }

        val sources = successes.flatMap { it.sources() }
        ImageText(
            text = sources.joinToString(separator = "\n\n") { source ->
                "=== ${source.engine} ===\n${source.text}"
            },
            engine = sources.joinToString(separator = "+") { it.engine },
            confidence = null,
            diagnostics = successes.flatMap { it.diagnostics } + failures.map { "${it.engine}: ${it.message}" },
            sourceTexts = sources,
        )
    }

    private val ImageTextReader.name: String
        get() = this::class.simpleName ?: "OCR reader"

    private sealed interface OcrAttempt {
        data class Success(val imageText: ImageText) : OcrAttempt
        data class Failure(val engine: String, val message: String) : OcrAttempt
    }

    companion object {
        fun defaultReaders(): List<ImageTextReader> =
            listOf(
                TesseractImageTextReader(pageSegmentationMode = 3),
                TesseractImageTextReader(pageSegmentationMode = 4),
                TesseractImageTextReader(pageSegmentationMode = 6),
                TesseractImageTextReader(pageSegmentationMode = 11),
                TesseractImageTextReader(pageSegmentationMode = 12),
                RapidOcrImageTextReader(),
            )
    }
}
