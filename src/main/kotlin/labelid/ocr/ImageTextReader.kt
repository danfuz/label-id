package labelid.ocr

import labelid.domain.ImageInput
import labelid.domain.ImageText

interface ImageTextReader {
    suspend fun readImage(image: ImageInput): ImageText
}

class ImageTextReadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
