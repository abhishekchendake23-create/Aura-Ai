package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.AttachmentDraft
import com.example.data.repository.KnowledgeRagEngine
import com.example.data.repository.RenderedScanPage
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerSheet(
    onSolvePaper: (draft: AttachmentDraft, prompt: String) -> Unit,
    onSaveToVault: (title: String, sourceType: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auraColors = LocalAuraColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pages by remember { mutableStateOf<List<RenderedScanPage>>(emptyList()) }
    var extractedText by remember { mutableStateOf("") }
    var docTitle by remember { mutableStateOf("Scanned Paper") }
    var isProcessing by remember { mutableStateOf(false) }
    var statusLabel by remember { mutableStateOf("Pick a multi-page PDF, multiple photos, or scan with camera.") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun runOcrAcrossPages(updatedPages: List<RenderedScanPage>) {
        if (updatedPages.isEmpty()) {
            extractedText = ""
            return
        }
        scope.launch {
            isProcessing = true
            statusLabel = "Extracting text via on-device OCR across ${updatedPages.size} page(s)..."
            val text = KnowledgeRagEngine.extractTextFromImageUris(
                context = context,
                imageUris = updatedPages.map { Uri.parse(it.uriString) }
            )
            extractedText = text
            statusLabel = if (text.isNotBlank()) {
                "Ready · ${updatedPages.size} page(s) & OCR extracted. Vision fallback also active!"
            } else {
                "Pages attached (${updatedPages.size}). Vision AI will read handwritten/math diagrams directly!"
            }
            isProcessing = false
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isProcessing = true
                statusLabel = "Rendering PDF pages..."
                docTitle = uri.lastPathSegment?.substringAfterLast('/') ?: "Scanned PDF"
                val rendered = KnowledgeRagEngine.renderPdfPagesToCache(context, uri, maxPages = 8)
                if (rendered.isEmpty()) {
                    statusLabel = "Could not open this PDF. Try selecting another PDF or photos."
                    isProcessing = false
                } else {
                    pages = (pages + rendered).take(10)
                    runOcrAcrossPages(pages)
                }
            }
        }
    }

    val multiPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newPages = uris.mapIndexed { idx, uri ->
                RenderedScanPage(
                    uriString = uri.toString(),
                    label = "Photo ${pages.size + idx + 1}"
                )
            }
            docTitle = "Exam Paper Scan"
            pages = (pages + newPages).take(10)
            runOcrAcrossPages(pages)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val captured = pendingCameraUri
        if (success && captured != null) {
            docTitle = "Camera Scan"
            val updated = (pages + RenderedScanPage(captured.toString(), "Scan ${pages.size + 1}")).take(10)
            pages = updated
            runOcrAcrossPages(updated)
        }
    }

    fun launchCameraCapture() {
        val dir = File(context.cacheDir, "camera_scans").apply {
            if (!exists()) mkdirs()
        }
        val file = File(dir, "scan_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            statusLabel = "Camera permission denied. You can still pick PDFs or gallery photos."
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DocumentScanner,
                    contentDescription = stringResource(R.string.cd_scan_paper),
                    tint = auraColors.sparkleStart,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "PDF & Exam Paper Scanner",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Multi-page Vision + On-Device OCR · Solve Q1..QN in one go",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3 Source Buttons: PDF, Multi-Photo, Camera
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScannerSourceTile(
                    icon = Icons.Rounded.PictureAsPdf,
                    title = "Pick PDF",
                    onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.weight(1f).testTag("scanner_pick_pdf")
                )
                ScannerSourceTile(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Multi Photos",
                    onClick = {
                        multiPhotoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f).testTag("scanner_pick_photos")
                )
                ScannerSourceTile(
                    icon = Icons.Rounded.CameraAlt,
                    title = "Camera",
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) launchCameraCapture()
                        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.weight(1f).testTag("scanner_camera_button")
                )
            }

            // Status bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pages.isNotEmpty()) auraColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Page thumbnails
            if (pages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pages.forEachIndexed { idx, page ->
                        Box(
                            modifier = Modifier
                                .size(width = 86.dp, height = 110.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(14.dp)
                                )
                        ) {
                            AsyncImage(
                                model = Uri.parse(page.uriString),
                                contentDescription = page.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "P${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                                    .clickable {
                                        val next = pages.toMutableList().apply { removeAt(idx) }
                                        pages = next
                                        runOcrAcrossPages(next)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove page ${idx + 1}",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = docTitle,
                    onValueChange = { docTitle = it },
                    label = { Text("Paper / Document Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = extractedText,
                    onValueChange = { extractedText = it },
                    label = { Text("Extracted OCR Text (Editable)") },
                    placeholder = { Text("OCR text or add extra instructions here...") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val textToSave = extractedText.trim()
                            if (textToSave.isNotBlank()) {
                                onSaveToVault(docTitle.ifBlank { "Scanned Paper" }, "Scan", textToSave)
                                onDismiss()
                            }
                        },
                        enabled = extractedText.isNotBlank() && !isProcessing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).testTag("scanner_save_vault")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save to Vault")
                    }

                    Button(
                        onClick = {
                            val first = pages.first()
                            val draft = AttachmentDraft(
                                uri = first.uriString,
                                displayName = "${docTitle.ifBlank { "Paper" }} (${pages.size} pages)",
                                isImage = true,
                                textSnippet = extractedText.trim().take(6000).ifBlank { null },
                                extraImageUris = pages.drop(1).take(5).map { it.uriString }
                            )
                            val prompt = "Solve EVERY SINGLE QUESTION visible across these ${pages.size} scanned page(s) sequentially from Q1 through QN with complete step-by-step explanations, KaTeX formulas, and final boxed answers."
                            onSolvePaper(draft, prompt)
                            onDismiss()
                        },
                        enabled = pages.isNotEmpty() && !isProcessing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).testTag("scanner_solve_all")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Solve Q1..QN")
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ScannerSourceTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
