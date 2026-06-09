package labelid.ocr

import kotlinx.coroutines.runBlocking
import labelid.domain.ImageInput
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class TesseractImageTextReaderTest {
    @Test
    fun readsTextFromGeneratedImageWhenTesseractIsAvailable() = runBlocking {
        assumeTrue(isTesseractAvailable(), "Tesseract is not installed on PATH")

        val imagePath = Files.createTempFile("label-id-ocr-", ".png")
        try {
            writeTestImage(imagePath)

            val result = TesseractImageTextReader(timeout = Duration.ofSeconds(10))
                .readImage(ImageInput(imagePath))

            assertTrue(result.text.contains("OLD TOM", ignoreCase = true), result.text)
            assertTrue(result.text.contains("750", ignoreCase = true), result.text)
        } finally {
            Files.deleteIfExists(imagePath)
        }
    }

    private fun isTesseractAvailable(): Boolean =
        runCatching {
            ProcessBuilder("tesseract", "--version")
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

    private fun writeTestImage(path: java.nio.file.Path) {
        val image = BufferedImage(1000, 360, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = Color.BLACK
            graphics.font = Font("SansSerif", Font.BOLD, 56)
            graphics.drawString("OLD TOM DISTILLERY", 60, 110)
            graphics.font = Font("SansSerif", Font.PLAIN, 44)
            graphics.drawString("750 mL", 60, 190)
            graphics.drawString("45% Alc./Vol.", 60, 260)
        } finally {
            graphics.dispose()
        }
        ImageIO.write(image, "png", path.toFile())
    }
}
