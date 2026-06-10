package labelid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import labelid.debug
import labelid.domain.CheckStatus
import labelid.domain.FieldCheck
import labelid.domain.ImageInput
import labelid.domain.VerificationReport
import labelid.domain.VerificationStatus
import labelid.ocr.EnsembleImageTextReader
import labelid.verification.VerificationService
import org.jetbrains.skia.Image as SkiaImage
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.nio.file.Files
import java.nio.file.Path

@Composable
fun LabelIdApp() {
    val service = remember { VerificationService(EnsembleImageTextReader()) }

    var selectedImage by remember { mutableStateOf<Path?>(null) }
    var applicationText by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<VerificationReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun selectImage(path: Path) {
        selectedImage = path
        report = null
        error = null
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF7F7F4),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Label ID", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Single label verification",
                        style = MaterialTheme.typography.subtitle1,
                        color = Color(0xFF555A5E),
                    )

                    ImagePicker(
                        selectedImage = selectedImage,
                        onPick = {
                            pickImagePath()?.let(::selectImage)
                        },
                    )

                    ImagePreview(selectedImage)

                    OutlinedTextField(
                        value = applicationText,
                        onValueChange = {
                            applicationText = it
                            report = null
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        label = { Text("Paste COLA/application text") },
                        placeholder = {
                            Text("Brand Name: OLD TOM DISTILLERY\nNet Contents: 750 mL\nAlcohol Content: 45%")
                        },
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = selectedImage != null && applicationText.isNotBlank() && !isRunning,
                            onClick = {
                                val imagePath = selectedImage ?: return@Button
                                scope.launch {
                                    isRunning = true
                                    error = null
                                    report = null
                                    try {
                                        report = service.verify(
                                            image = ImageInput(imagePath),
                                            rawApplicationText = applicationText,
                                        )
                                    } catch (ex: Exception) {
                                        error = ex.message ?: "Verification failed."
                                    } finally {
                                        isRunning = false
                                    }
                                }
                            },
                        ) {
                            Text(if (isRunning) "Reading Label" else "Verify Label")
                        }

                        if (isRunning) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { ErrorPanel(it) }
                    report?.let { VerificationResults(it) } ?: EmptyResults()
                }
            }
        }
    }
}

@Composable
private fun ImagePicker(
    selectedImage: Path?,
    onPick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPick) {
            Text("Choose Image")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            selectedImage?.fileName?.toString() ?: "No image selected",
            color = Color(0xFF555A5E),
            maxLines = 1,
        )
    }
}

@Composable
private fun ImagePreview(path: Path?) {
    val bitmap = remember(path) { path?.let(::loadImageBitmap) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD5D8DA), RoundedCornerShape(8.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            path == null -> Text("Choose image", color = Color(0xFF777D82))
            bitmap == null -> Text("Preview unavailable", color = Color(0xFF777D82))
            else -> Image(
                bitmap = bitmap,
                contentDescription = "Selected label image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun VerificationResults(report: VerificationReport) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Result", style = MaterialTheme.typography.h6)
        Spacer(Modifier.width(10.dp))
        StatusChip(report.status)
        Spacer(Modifier.width(10.dp))
        Text("${report.elapsedMillis} ms", color = Color(0xFF555A5E))
    }
    Text(
        "OCR sources: ${report.imageText.sources().joinToString { it.engine }}",
        color = Color(0xFF555A5E),
    )

    Text("Checks", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.checks.forEach { CheckRow(it) }
    }

    Divider(Modifier.padding(vertical = 6.dp))

    Text("Parsed Application Fields", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    val comparableFields = report.expected.comparableFields()
    if (comparableFields.isEmpty()) {
        Text("No comparable fields parsed.", color = Color(0xFF777D82))
    } else {
        comparableFields.forEach { field ->
            Text("${field.name}: ${field.value}")
        }
    }

    if (debug) {
        Divider(Modifier.padding(vertical = 6.dp))

        CopyableTextPanel(
            title = "OCR Text",
            value = report.imageText.text.ifBlank { "No text returned." },
            height = 220,
        )

        if (report.imageText.diagnostics.isNotEmpty()) {
            Divider(Modifier.padding(vertical = 6.dp))
            CopyableTextPanel(
                title = "OCR Diagnostics",
                value = report.imageText.diagnostics.joinToString(separator = "\n"),
                height = 160,
            )
        }
    }
}

@Composable
private fun CheckRow(check: FieldCheck) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD5D8DA), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.fieldName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            CheckStatusChip(check.status)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(check.message, color = Color(0xFF44484C))
                Text("Expected: ${check.expected}", style = MaterialTheme.typography.caption, color = Color(0xFF5B6167))
                check.observed?.let {
                    Text("Observed: $it", style = MaterialTheme.typography.caption, color = Color(0xFF5B6167))
                }
            }
        }
    }
}

@Composable
private fun EmptyResults() {
    Box(
        modifier = Modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Results will appear after verification.", color = Color(0xFF777D82))
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF0EB), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD56A4A), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(message, color = Color(0xFF8C2F16))
        }
    }
}

@Composable
private fun CopyableTextPanel(
    title: String,
    value: String,
    height: Int,
) {
    Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        modifier = Modifier.fillMaxWidth().height(height.dp),
    )
}

@Composable
private fun StatusChip(status: VerificationStatus) {
    Box(
        modifier = Modifier
            .background(statusColor(status), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(status.name, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckStatusChip(status: CheckStatus) {
    Box(
        modifier = Modifier
            .background(checkStatusColor(status), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(status.name.replace('_', ' '), color = Color.White, style = MaterialTheme.typography.caption)
    }
}

private fun statusColor(status: VerificationStatus): Color =
    when (status) {
        VerificationStatus.PASS -> Color(0xFF277A4B)
        VerificationStatus.REVIEW -> Color(0xFF8A6D1D)
        VerificationStatus.FAIL -> Color(0xFFB13A2F)
    }

private fun checkStatusColor(status: CheckStatus): Color =
    when (status) {
        CheckStatus.PASS -> Color(0xFF277A4B)
        CheckStatus.REVIEW -> Color(0xFF8A6D1D)
        CheckStatus.FAIL -> Color(0xFFB13A2F)
        CheckStatus.NOT_ASSESSED -> Color(0xFF5B6570)
    }

private fun pickImagePath(): Path? {
    val dialog = FileDialog(null as Frame?, "Choose label image", FileDialog.LOAD).apply {
        isMultipleMode = false
        filenameFilter = FilenameFilter { _, name ->
            isSupportedImageName(name)
        }
    }
    dialog.isVisible = true
    return dialog.files.firstOrNull()?.toPath()
}

private fun isSupportedImagePath(path: Path): Boolean =
    isSupportedImageName(path.fileName?.toString().orEmpty())

private fun isSupportedImageName(name: String): Boolean =
    name.lowercase().let {
        it.endsWith(".png") ||
            it.endsWith(".jpg") ||
            it.endsWith(".jpeg") ||
            it.endsWith(".tif") ||
            it.endsWith(".tiff") ||
            it.endsWith(".bmp")
    }

private fun loadImageBitmap(path: Path): ImageBitmap? =
    runCatching {
        SkiaImage.makeFromEncoded(Files.readAllBytes(path)).toComposeImageBitmap()
    }.getOrNull()
