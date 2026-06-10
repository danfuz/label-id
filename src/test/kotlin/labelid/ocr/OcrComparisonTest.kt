package labelid.ocr

import kotlinx.coroutines.runBlocking
import labelid.domain.CheckStatus
import labelid.domain.ExpectedField
import labelid.domain.FieldKind
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.VerificationReport
import labelid.parsing.ApplicationTextParser
import labelid.verification.AlcoholContentParser
import labelid.verification.NetContentsParser
import labelid.verification.TextNormalizer
import labelid.verification.VerificationService
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.absolutePathString
import kotlin.test.Test

class OcrComparisonTest {
    @Test
    fun comparesAvailableOcrEnginesOnAbcFixture() = runBlocking {
        assumeTrue(Files.exists(fixtureImage), "ABC sample image fixture is missing")
        assumeTrue(Files.exists(fixtureColaText), "ABC COLA text fixture is missing")

        val rawColaText = Files.readString(fixtureColaText)
        val expectedFields = ApplicationTextParser().parse(rawColaText).comparableFields()
        val scaledImage = upscaleImage(fixtureImage, scale = 3)

        val results = try {
            buildList {
                add(tesseractRun("Tesseract psm 3", fixtureImage, pageSegmentationMode = 3))
                add(tesseractRun("Tesseract psm 4", fixtureImage, pageSegmentationMode = 4))
                add(tesseractRun("Tesseract psm 6", fixtureImage, pageSegmentationMode = 6))
                add(tesseractRun("Tesseract psm 11", fixtureImage, pageSegmentationMode = 11))
                add(tesseractRun("Tesseract psm 12", fixtureImage, pageSegmentationMode = 12))
                add(tesseractRun("Tesseract 3x upscale psm 11", scaledImage, pageSegmentationMode = 11))
                add(rapidOcrRun(fixtureImage))
            }.map { result ->
                result.withVerification(rawColaText, expectedFields)
            }
        } finally {
            Files.deleteIfExists(scaledImage)
        }

        val reportPath = writeComparisonReport(rawColaText, expectedFields, results)
        println("OCR comparison report written to ${reportPath.absolutePathString()}")

        assumeTrue(
            results.any { it.status == OcrRunStatus.SUCCESS },
            "No OCR engines were available. Comparison report written to ${reportPath.absolutePathString()}",
        )
    }

    private fun tesseractRun(
        name: String,
        image: Path,
        pageSegmentationMode: Int,
    ): OcrRunResult =
        runCommand(
            name = name,
            command = listOf(
                "tesseract",
                image.absolutePathString(),
                "stdout",
                "-l",
                "eng",
                "--psm",
                pageSegmentationMode.toString(),
                "-c",
                "preserve_interword_spaces=1",
            ),
            timeout = Duration.ofSeconds(30),
        )

    private suspend fun OcrRunResult.withVerification(
        rawColaText: String,
        expectedFields: List<ExpectedField>,
    ): OcrRunResult {
        if (status != OcrRunStatus.SUCCESS || text.isBlank()) return this

        val verification = VerificationService(StaticTextReader(text))
            .verify(ImageInput(fixtureImage), rawColaText)
        val fieldHits = expectedFields.count { fieldMatches(it, text) }

        return copy(
            verification = verification,
            fieldHits = fieldHits,
            fieldCount = expectedFields.size,
        )
    }

    private fun rapidOcrRun(image: Path): OcrRunResult =
        runCommand(
            name = "RapidOCR PP-OCRv5",
            command = listOf(
                RapidOcrImageTextReader.defaultPythonExecutable(),
                "-c",
                RapidOcrImageTextReader.rapidOcrScriptForTesting(),
                image.absolutePathString(),
            ),
            timeout = RapidOcrImageTextReader.defaultTimeout(),
            textExtractor = RapidOcrImageTextReader::extractRapidOcrText,
        )

    private fun runCommand(
        name: String,
        command: List<String>,
        timeout: Duration,
        environmentOverrides: Map<String, String> = emptyMap(),
        textExtractor: (String) -> String = { it },
    ): OcrRunResult {
        val startedAt = System.nanoTime()
        val process = try {
            ProcessBuilder(command)
                .apply { environment().putAll(environmentOverrides) }
                .redirectErrorStream(false)
                .start()
        } catch (ex: Exception) {
            return OcrRunResult(
                name = name,
                status = OcrRunStatus.SKIPPED,
                elapsedMillis = elapsedMillisSince(startedAt),
                error = "Could not start command: ${ex.message}",
                command = command,
            )
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return OcrRunResult(
                name = name,
                status = OcrRunStatus.FAILED,
                elapsedMillis = elapsedMillisSince(startedAt),
                error = "Timed out after ${timeout.seconds} seconds.",
                command = command,
            )
        }

        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        val elapsedMillis = elapsedMillisSince(startedAt)

        if (process.exitValue() != 0) {
            return OcrRunResult(
                name = name,
                status = OcrRunStatus.FAILED,
                elapsedMillis = elapsedMillis,
                error = "Exit ${process.exitValue()}: ${stderr.ifBlank { stdout.ifBlank { "no output" } }}",
                command = command,
                diagnostics = stderr,
            )
        }

        return OcrRunResult(
            name = name,
            status = OcrRunStatus.SUCCESS,
            elapsedMillis = elapsedMillis,
            text = textExtractor(stdout.ifBlank { stderr }),
            command = command,
            diagnostics = stderr.takeIf { stdout.isNotBlank() }.orEmpty(),
        )
    }

    private fun writeComparisonReport(
        rawColaText: String,
        expectedFields: List<ExpectedField>,
        results: List<OcrRunResult>,
    ): Path {
        val reportDir = Path.of("build", "reports", "ocr-comparison")
        Files.createDirectories(reportDir)
        val reportPath = reportDir.resolve("abc-single-barrel-straight-rye-whisky.md")

        val content = buildString {
            appendLine("# OCR Comparison: ABC Single Barrel Straight Rye Whisky")
            appendLine()
            appendLine("- Generated: ${Instant.now()}")
            appendLine("- Image: `${fixtureImage}`")
            appendLine("- COLA text: `${fixtureColaText}`")
            appendLine()
            appendLine("## Expected COLA Text")
            appendLine()
            appendCodeBlock(rawColaText)
            appendLine()
            appendLine("## Expected Comparable Fields")
            appendLine()
            expectedFields.forEach { appendLine("- ${it.name}: ${it.value}") }
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine("| Engine | Run | OCR ms | Field hits | App status | PASS | REVIEW | FAIL | Notes |")
            appendLine("|---|---:|---:|---:|---|---:|---:|---:|---|")
            results.forEach { result ->
                val verification = result.verification
                appendLine(
                    listOf(
                        result.name,
                        result.status.name,
                        result.elapsedMillis.toString(),
                        "${result.fieldHits}/${result.fieldCount}",
                        verification?.status?.name.orEmpty(),
                        verification?.count(CheckStatus.PASS)?.toString().orEmpty(),
                        verification?.count(CheckStatus.REVIEW)?.toString().orEmpty(),
                        verification?.count(CheckStatus.FAIL)?.toString().orEmpty(),
                        result.error.orEmpty(),
                    ).joinToMarkdownTableRow(),
                )
            }
            appendLine()
            appendLine("## Verification Details")
            appendLine()
            results.forEach { result ->
                appendLine("### ${result.name}")
                appendLine()
                val verification = result.verification
                if (verification == null) {
                    appendLine(result.error ?: "No verification report.")
                } else {
                    appendLine("| Check | Status | Observed | Message |")
                    appendLine("|---|---|---|---|")
                    verification.checks.forEach { check ->
                        appendLine(
                            listOf(
                                check.fieldName,
                                check.status.name,
                                check.observed.orEmpty(),
                                check.message,
                            ).joinToMarkdownTableRow(),
                        )
                    }
                }
                appendLine()
            }
            appendLine("## Commands")
            appendLine()
            results.forEach { result ->
                appendLine("### ${result.name}")
                appendLine()
                appendCodeBlock(result.command.joinToString(" "))
                appendLine()
            }
            appendLine("## OCR Output")
            appendLine()
            results.forEach { result ->
                appendLine("### ${result.name}")
                appendLine()
                if (result.status == OcrRunStatus.SUCCESS) {
                    appendCodeBlock(result.text.ifBlank { "(blank output)" })
                    if (result.diagnostics.isNotBlank()) {
                        appendLine()
                        appendLine("Diagnostics:")
                        appendCodeBlock(result.diagnostics)
                    }
                } else {
                    appendLine(result.error ?: "No output.")
                }
                appendLine()
            }
            appendLine("## RapidOCR Notes")
            appendLine()
            appendLine(
                "Set `LABEL_ID_RAPIDOCR_PYTHON` to a Python executable with `rapidocr` and `onnxruntime` " +
                    "installed. `LABEL_ID_RAPIDOCR_TIMEOUT_SECONDS` controls the RapidOCR timeout.",
            )
        }

        Files.writeString(reportPath, content)
        return reportPath
    }

    private fun upscaleImage(source: Path, scale: Int): Path {
        val original = ImageIO.read(source.toFile()) ?: error("Could not read $source")
        val scaled = BufferedImage(original.width * scale, original.height * scale, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(original, 0, 0, scaled.width, scaled.height, null)
        } finally {
            graphics.dispose()
        }

        val output = Files.createTempFile("label-id-ocr-upscaled-", ".png")
        ImageIO.write(scaled, "png", output.toFile())
        return output
    }

    private fun fieldMatches(field: ExpectedField, text: String): Boolean =
        when (field.kind) {
            FieldKind.TEXT -> TextNormalizer.containsLoose(text, field.value) ||
                TextNormalizer.containsOrderedTokenWindow(text, field.value)
            FieldKind.ALCOHOL_CONTENT -> {
                val expected = AlcoholContentParser.parseExpected(field.value)
                expected != null && AlcoholContentParser.parseAll(text).any { kotlin.math.abs(it - expected) <= 0.01 }
            }

            FieldKind.NET_CONTENTS -> {
                val expected = NetContentsParser.parseExpected(field.value)
                expected != null && NetContentsParser.parseAll(text).any { it.equivalentTo(expected) }
            }
        }

    private fun VerificationReport.count(status: CheckStatus): Int =
        checks.count { it.status == status }

    private fun List<String>.joinToMarkdownTableRow(): String =
        joinToString(prefix = "| ", separator = " | ", postfix = " |") { it.replace("|", "\\|").replace("\n", "<br>") }

    private fun StringBuilder.appendCodeBlock(value: String) {
        appendLine("```text")
        appendLine(value)
        appendLine("```")
    }

    private fun elapsedMillisSince(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private class StaticTextReader(
        private val text: String,
    ) : ImageTextReader {
        override suspend fun readImage(image: ImageInput): ImageText =
            ImageText(text = text, engine = "comparison")
    }

    private data class OcrRunResult(
        val name: String,
        val status: OcrRunStatus,
        val elapsedMillis: Long,
        val text: String = "",
        val error: String? = null,
        val command: List<String> = emptyList(),
        val diagnostics: String = "",
        val verification: VerificationReport? = null,
        val fieldHits: Int = 0,
        val fieldCount: Int = 0,
    )

    private enum class OcrRunStatus {
        SUCCESS,
        SKIPPED,
        FAILED,
    }

    private companion object {
        private val fixtureImage = Path.of("test", "abc-single-barrel-straight-rye-whisky.jpg")
        private val fixtureColaText = Path.of("test", "abc-single-barrel-straight-rye-whisky-cola.txt")
    }
}
