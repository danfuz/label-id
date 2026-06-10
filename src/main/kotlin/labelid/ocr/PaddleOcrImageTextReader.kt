package labelid.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import labelid.domain.ImageInput
import labelid.domain.ImageText
import labelid.domain.ImageTextSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class PaddleOcrImageTextReader(
    private val pythonExecutable: String = defaultPythonExecutable(),
    private val cacheHome: String = System.getenv("LABEL_ID_PADDLEOCR_CACHE_HOME")
        ?: Path.of(System.getProperty("user.home"), ".label-id", "paddleocr-cache").toString(),
    private val timeout: Duration = Duration.ofSeconds(45),
) : ImageTextReader {
    override suspend fun readImage(image: ImageInput): ImageText = withContext(Dispatchers.IO) {
        val scriptPath = Files.createTempFile("label-id-paddleocr-", ".py")
        Files.writeString(scriptPath, paddleOcrScript)
        val command = listOf(
            pythonExecutable,
            scriptPath.toAbsolutePath().toString(),
            image.path.toAbsolutePath().toString(),
        )

        try {
            val process = try {
                ProcessBuilder(command)
                    .apply { environment()["PADDLE_PDX_CACHE_HOME"] = cacheHome }
                    .redirectErrorStream(false)
                    .start()
            } catch (ex: Exception) {
                throw ImageTextReadException(
                    "Could not start PaddleOCR using `$pythonExecutable`. Install PaddleOCR and set " +
                        "LABEL_ID_PADDLEOCR_PYTHON to its Python executable.",
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
                    "PaddleOCR failed using `$pythonExecutable` with exit code ${process.exitValue()}: " +
                        stderr.ifBlank { stdout.ifBlank { "no output" } },
                )
            }

            val rawOutput = stdout.ifBlank { stderr }
            val sources = extractPaddleTextSources(rawOutput)
            ImageText(
                text = sources.joinToString(separator = "\n\n") { it.text },
                engine = "paddleocr",
                confidence = null,
                diagnostics = listOfNotNull(stderr.takeIf { stdout.isNotBlank() && it.isNotBlank() }),
                sourceTexts = sources,
            )
        } finally {
            Files.deleteIfExists(scriptPath)
        }
    }

    companion object {
        private val recTextsPattern = Regex(
            pattern = """['"]rec_texts['"]\s*:\s*\[(.*?)]""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val quotedStringPattern = Regex("""'([^'\\]*(?:\\.[^'\\]*)*)'|"((?:\\.|[^"\\])*)"""")
        private const val REC_TEXT_MARKER = "__LABEL_ID_PADDLEOCR_REC_TEXT__"
        private const val SPATIAL_TEXT_MARKER = "__LABEL_ID_PADDLEOCR_SPATIAL_TEXT__"

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
            environment["LABEL_ID_PADDLEOCR_PYTHON"]
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
                    add(Path.of(localAppData, "label-id", "paddleocr-venv", "Scripts", "python.exe"))
                }

            add(Path.of(userHome, ".label-id", "paddleocr-venv", "Scripts", "python.exe"))
            add(Path.of(userHome, ".label-id", "paddleocr-venv", "bin", "python"))
            add(Path.of("/tmp", "label-id-paddleocr-venv", "bin", "python"))
        }

        fun extractPaddleTextSources(output: String): List<ImageTextSource> {
            val recText = output.sectionAfterMarker(REC_TEXT_MARKER, SPATIAL_TEXT_MARKER)
            val spatialText = output.sectionAfterMarker(SPATIAL_TEXT_MARKER)
            if (recText != null || spatialText != null) {
                return buildList {
                    recText?.takeIf { it.isNotBlank() }?.let {
                        add(ImageTextSource(text = it, engine = "paddleocr"))
                    }
                    spatialText
                        ?.takeIf { it.isNotBlank() && it != recText }
                        ?.let { add(ImageTextSource(text = it, engine = "paddleocr-spatial")) }
                }.ifEmpty { listOf(ImageTextSource(text = output.trim(), engine = "paddleocr")) }
            }

            return listOf(ImageTextSource(text = extractPaddleText(output), engine = "paddleocr"))
        }

        fun extractPaddleText(output: String): String {
            output.sectionAfterMarker(REC_TEXT_MARKER, SPATIAL_TEXT_MARKER)?.let {
                return it
            }

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

            REC_TEXT_MARKER = "$REC_TEXT_MARKER"
            SPATIAL_TEXT_MARKER = "$SPATIAL_TEXT_MARKER"

            def box_values(box):
                values = []
                for value in box:
                    values.append(int(value))
                return values

            def overlaps_vertically(left, right):
                overlap = min(left[3], right[3]) - max(left[1], right[1])
                if overlap <= 0:
                    return False
                left_height = max(1, left[3] - left[1])
                right_height = max(1, right[3] - right[1])
                return overlap / min(left_height, right_height) >= 0.65

            def same_line(line_rows, candidate):
                candidate_center_y = (candidate["box"][1] + candidate["box"][3]) / 2
                average_center_y = sum((row["box"][1] + row["box"][3]) / 2 for row in line_rows) / len(line_rows)
                median_height = sorted(max(1, row["box"][3] - row["box"][1]) for row in line_rows)[len(line_rows) // 2]
                close_center = abs(average_center_y - candidate_center_y) <= max(24, min(90, median_height * 0.45))
                return close_center or any(overlaps_vertically(row["box"], candidate["box"]) for row in line_rows)

            def spatial_lines(texts, boxes):
                rows = []
                for text, box in zip(texts, boxes):
                    clean_text = str(text).strip()
                    if not clean_text:
                        continue
                    values = box_values(box)
                    rows.append({"text": clean_text, "box": values})
                lines = []
                for row in sorted(rows, key=lambda item: (item["box"][1] + item["box"][3]) / 2):
                    for line in lines:
                        if same_line(line, row):
                            line.append(row)
                            break
                    else:
                        lines.append([row])
                spatial_output = []
                sorted_lines = sorted(
                    lines,
                    key=lambda line: sum((row["box"][1] + row["box"][3]) / 2 for row in line) / len(line),
                )
                for line in sorted_lines:
                    sorted_line = sorted(line, key=lambda item: item["box"][0])
                    spatial_output.append(" ".join(row["text"] for row in sorted_line))
                return spatial_output

            ocr = PaddleOCR(
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
                return_word_box=True,
            )
            rec_texts = []
            spatial_texts = []
            for result in ocr.predict(sys.argv[1], return_word_box=True):
                data = result.json["res"]
                result_texts = data.get("rec_texts") or []
                rec_texts.extend(str(text) for text in result_texts)
                result_boxes = data.get("rec_boxes")
                if result_boxes is None:
                    result_boxes = []
                spatial_texts.extend(spatial_lines(result_texts, result_boxes))

            print(REC_TEXT_MARKER)
            print("\n".join(rec_texts))
            print(SPATIAL_TEXT_MARKER)
            print("\n".join(spatial_texts))
        """.trimIndent()

        fun paddleOcrScriptForTesting(): String = paddleOcrScript

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

        private fun String.unescapeQuotedString(): String =
            replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
    }
}
