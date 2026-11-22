package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import android.content.Intent
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.example.R
import com.example.data.repository.AuraPdfExporter
import com.example.ui.screens.ArtifactPreviewDialog
import com.example.ui.screens.isPreviewableArtifactLanguage
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.delay

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val text: String) : MdBlock
    data class NumberedItem(val index: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class CodeBlock(val language: String, val code: String) : MdBlock
    data class MathBlock(val latex: String) : MdBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

@Composable
fun MarkdownRenderer(
    markdown: String,
    isStreaming: Boolean = false,
    onAutoFixArtifactError: (errorLog: String, code: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEachIndexed { index, block ->
                val isTailStreamingBlock = isStreaming && index == blocks.lastIndex
                when (block) {
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineMedium
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    is MdBlock.Paragraph -> {
                        Text(
                            text = parseInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    is MdBlock.BulletItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.NumberedItem -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${block.index}.",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = parseInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    is MdBlock.CodeBlock -> {
                        if (block.language.equals("sandbox-output", ignoreCase = true)) {
                            SandboxOutputCard(output = block.code)
                        } else {
                            CodeBlockCard(
                                language = block.language,
                                code = block.code,
                                isLiveStreaming = isTailStreamingBlock,
                                onAutoFixArtifactError = onAutoFixArtifactError
                            )
                        }
                    }

                    is MdBlock.MathBlock -> {
                        MathBlockCard(latex = block.latex)
                    }

                    is MdBlock.TableBlock -> {
                        MarkdownTableCard(headers = block.headers, rows = block.rows)
                    }

                    MdBlock.Divider -> {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableCard(
    headers: List<String>,
    rows: List<List<String>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        if (headers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                headers.forEach { cell ->
                    Text(
                        text = parseInlineMarkdown(cell),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 84.dp, max = 200.dp)
                    )
                }
            }
        }
        rows.forEachIndexed { idx, row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .background(
                        if (idx % 2 == 1) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { cell ->
                    Text(
                        text = parseInlineMarkdown(cell),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.widthIn(min = 84.dp, max = 200.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SandboxOutputCard(output: String) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val cleanOutput = remember(output) { output.trim() }
    val isOk = !cleanOutput.contains("FAILED", ignoreCase = true) &&
        !cleanOutput.contains("ERROR", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(auraColors.codeBackground)
            .border(
                1.dp,
                if (isOk) auraColors.emerald.copy(alpha = 0.55f) else MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isOk) auraColors.emeraldContainer else MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (isOk) Icons.Rounded.Verified else Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = if (isOk) auraColors.emerald else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isOk) "Aura Sandbox · Executed & Verified" else "Aura Sandbox · Execution Output",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOk) auraColors.emerald else MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(cleanOutput))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_code),
                    tint = if (copied) auraColors.emerald else auraColors.codeText.copy(alpha = 0.8f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) auraColors.emerald else auraColors.codeText.copy(alpha = 0.8f)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = cleanOutput,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = auraColors.codeText
            )
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    isLiveStreaming: Boolean = false,
    onAutoFixArtifactError: (errorLog: String, code: String) -> Unit = { _, _ -> }
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val errorColor = MaterialTheme.colorScheme.error
    var copied by remember { mutableStateOf(false) }
    var showFullScreen by remember { mutableStateOf(false) }
    var showArtifactPreview by remember { mutableStateOf(false) }

    val (cleanLang, fileBadge) = remember(language, code) {
        val rawLang = language.trim()
        val colonIdx = rawLang.indexOf(':')
        val parsedLang = if (colonIdx > 0) rawLang.substring(0, colonIdx).trim() else rawLang
        val headerFile = if (colonIdx > 0) rawLang.substring(colonIdx + 1).trim().takeIf { it.isNotBlank() } else null
        val firstLine = code.lineSequence().firstOrNull()?.trim().orEmpty()
        val commentFile = Regex("^(?://|#|--|/\\*)\\s*File:\\s*([^\\s*]+)", RegexOption.IGNORE_CASE)
            .find(firstLine)?.groupValues?.getOrNull(1)?.trim()
        parsedLang.ifBlank { "code" }.lowercase() to (headerFile ?: commentFile)
    }
    val canPreview = remember(cleanLang) { isPreviewableArtifactLanguage(cleanLang) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val highlightedCode = remember(code, cleanLang, auraColors.codeText) {
        if (cleanLang.equals("diff", ignoreCase = true)) {
            highlightDiffSyntax(
                code = code,
                baseColor = auraColors.codeText,
                addedColor = auraColors.emerald,
                removedColor = errorColor,
                hunkColor = auraColors.sparkleStart
            )
        } else {
            highlightCodeSyntax(
                code = code,
                baseColor = auraColors.codeText,
                keywordColor = auraColors.sparkleMid,
                stringColor = auraColors.emerald,
                commentColor = auraColors.codeText.copy(alpha = 0.48f),
                numberColor = auraColors.sparkleStart,
                annotationColor = auraColors.sparkleEnd
            )
        }
    }

    val lineCount = remember(code) { code.lines().size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(auraColors.codeBackground)
            .border(
                1.dp,
                if (isLiveStreaming) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(auraColors.codeHeader)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                if (isLiveStreaming) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(auraColors.emerald)
                    )
                }
                Text(
                    text = if (fileBadge != null) "$fileBadge · $cleanLang · $lineCount L" else "$cleanLang · $lineCount lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (canPreview) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(auraColors.emeraldContainer)
                            .clickable { showArtifactPreview = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("preview_artifact_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Preview live artifact",
                            tint = auraColors.emerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = auraColors.emerald
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                val (fileUri, fileName) = AuraPdfExporter.exportCodeSnippetUri(
                                    context = context,
                                    code = code,
                                    language = cleanLang,
                                    suggestedFilename = fileBadge
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Export $fileName")
                                )
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("export_code_file_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.IosShare,
                        contentDescription = "Export code as file",
                        tint = auraColors.codeText.copy(alpha = 0.78f),
                        modifier = Modifier.size(13.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showFullScreen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("expand_code_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInFull,
                        contentDescription = "Expand code full screen",
                        tint = auraColors.codeText.copy(alpha = 0.78f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Expand",
                        style = MaterialTheme.typography.labelSmall,
                        color = auraColors.codeText.copy(alpha = 0.85f)
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("copy_code_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedContent(targetState = copied, label = "copy_icon") { isCopied ->
                        Icon(
                            imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.cd_copy_code),
                            tint = if (isCopied) auraColors.emerald else auraColors.codeText.copy(alpha = 0.78f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) auraColors.emerald else auraColors.codeText.copy(alpha = 0.85f)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            Text(
                text = highlightedCode,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.5.sp,
                lineHeight = 19.sp
            )
        }
    }

    if (showFullScreen) {
        FullScreenCodeDialog(
            language = language,
            code = code,
            highlightedCode = highlightedCode,
            onDismiss = { showFullScreen = false }
        )
    }

    if (showArtifactPreview) {
        ArtifactPreviewDialog(
            language = language,
            code = code,
            onAutoFixError = onAutoFixArtifactError,
            onDismiss = { showArtifactPreview = false }
        )
    }
}

@Composable
private fun FullScreenCodeDialog(
    language: String,
    code: String,
    highlightedCode: AnnotatedString,
    onDismiss: () -> Unit
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.lines() }
    val lineNumbersText = remember(lines.size) {
        (1..lines.size).joinToString("\n")
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(auraColors.codeBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(auraColors.codeHeader)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close code inspector",
                            tint = auraColors.codeText
                        )
                    }
                    Column {
                        Text(
                            text = "${language.ifBlank { "code" }.uppercase()} Inspector",
                            style = MaterialTheme.typography.titleSmall,
                            color = auraColors.codeText
                        )
                        Text(
                            text = "${lines.size} lines · Aura Code Studio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_code),
                        tint = if (copied) auraColors.emerald else auraColors.codeText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (copied) "Copied" else "Copy All",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (copied) auraColors.emerald else auraColors.codeText
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = lineNumbersText,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp,
                        color = auraColors.codeText.copy(alpha = 0.35f),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 16.dp)
                    ) {
                        Text(
                            text = highlightedCode,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
    "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "throw", "import", "package", "private", "public", "internal", "protected", "override",
    "suspend", "inline", "lateinit", "by", "true", "false", "null", "def", "async",
    "await", "from", "as", "with", "yield", "lambda", "function", "const", "let",
    "export", "default", "switch", "case", "break", "continue", "new", "this", "super",
    "void", "int", "float", "double", "boolean", "string", "SELECT", "FROM", "WHERE",
    "INSERT", "UPDATE", "DELETE", "JOIN", "CREATE", "TABLE"
)

private fun highlightDiffSyntax(
    code: String,
    baseColor: Color,
    addedColor: Color,
    removedColor: Color,
    hunkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { idx, line ->
            val style = when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") ->
                    SpanStyle(color = hunkColor, fontWeight = FontWeight.SemiBold)
                line.startsWith("+") ->
                    SpanStyle(color = addedColor, background = addedColor.copy(alpha = 0.12f))
                line.startsWith("-") ->
                    SpanStyle(color = removedColor, background = removedColor.copy(alpha = 0.12f))
                else -> SpanStyle(color = baseColor)
            }
            withStyle(style) {
                append(line)
            }
            if (idx < lines.lastIndex) append('\n')
        }
    }
}

private fun highlightCodeSyntax(
    code: String,
    baseColor: Color,
    keywordColor: Color,
    stringColor: Color,
    commentColor: Color,
    numberColor: Color,
    annotationColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when {
                // Single-line // or # comment
                code.startsWith("//", i) || (ch == '#' && (i == 0 || code[i - 1] == '\n' || code[i - 1].isWhitespace())) -> {
                    val end = code.indexOf('\n', i).let { if (it == -1) code.length else it }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Multi-line /* ... */ comment
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).let { if (it == -1) code.length else it + 2 }
                    withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Strings "..." or '...'
                ch == '"' || ch == '\'' -> {
                    var end = i + 1
                    while (end < code.length) {
                        if (code[end] == '\\') {
                            end += 2
                            continue
                        }
                        if (code[end] == ch || code[end] == '\n') {
                            if (code[end] == ch) end++
                            break
                        }
                        end++
                    }
                    withStyle(SpanStyle(color = stringColor)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Annotations @Composable, @Serializable, etc.
                ch == '@' && i + 1 < code.length && code[i + 1].isLetter() -> {
                    var end = i + 1
                    while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                    withStyle(SpanStyle(color = annotationColor)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Numbers
                ch.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit()) -> {
                    var end = i
                    while (end < code.length && (code[end].isDigit() || code[end] == '.' || code[end] == '_' || code[end] == 'L' || code[end] == 'f')) {
                        end++
                    }
                    withStyle(SpanStyle(color = numberColor)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Identifiers / Keywords / PascalCase Types
                ch.isLetter() || ch == '_' -> {
                    var end = i
                    while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                    val word = code.substring(i, end)
                    val style = when {
                        word in CODE_KEYWORDS -> SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)
                        word.first().isUpperCase() && word.any { it.isLowerCase() } -> SpanStyle(color = annotationColor)
                        else -> SpanStyle(color = baseColor)
                    }
                    withStyle(style) {
                        append(word)
                    }
                    i = end
                }
                else -> {
                    withStyle(SpanStyle(color = baseColor)) {
                        append(ch)
                    }
                    i++
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val lines = input.lines()
    val blocks = mutableListOf<MdBlock>()
    val paragraphBuffer = mutableListOf<String>()
    var i = 0

    fun flushParagraph() {
        if (paragraphBuffer.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraphBuffer.joinToString("\n")))
            paragraphBuffer.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Fenced Code Blocks
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        // Display Math Blocks: $$...$$ or \[...\]
        if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
            flushParagraph()
            val isDollar = trimmed.startsWith("$$")
            val endDelim = if (isDollar) "$$" else "\\]"
            val startDelim = if (isDollar) "$$" else "\\["

            val afterStart = trimmed.removePrefix(startDelim)
            if (afterStart.contains(endDelim)) {
                val content = afterStart.substringBeforeLast(endDelim).trim()
                if (content.isNotEmpty()) {
                    blocks.add(MdBlock.MathBlock(content))
                }
                i++
                continue
            } else {
                val mathLines = mutableListOf<String>()
                if (afterStart.isNotBlank()) mathLines.add(afterStart)
                i++
                while (i < lines.size) {
                    val mLine = lines[i].trim()
                    if (mLine.contains(endDelim)) {
                        val beforeEnd = mLine.substringBefore(endDelim)
                        if (beforeEnd.isNotBlank()) mathLines.add(beforeEnd)
                        break
                    }
                    mathLines.add(lines[i])
                    i++
                }
                val content = mathLines.joinToString("\n").trim()
                if (content.isNotEmpty()) {
                    blocks.add(MdBlock.MathBlock(content))
                }
                i++
                continue
            }
        }

        // Markdown Tables
        if (trimmed.startsWith("|") && trimmed.endsWith("|") &&
            i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|[\\s:|-]+\\|$"))
        ) {
            flushParagraph()
            val headers = trimmed.trim('|').split("|").map { it.trim() }
            i += 2 // skip header and separator
            val tableRows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rTrim = lines[i].trim()
                if (rTrim.startsWith("|") && rTrim.contains("|")) {
                    tableRows.add(rTrim.trim('|').split("|").map { it.trim() })
                    i++
                } else {
                    break
                }
            }
            blocks.add(MdBlock.TableBlock(headers, tableRows))
            continue
        }

        when {
            trimmed.isEmpty() -> flushParagraph()
            trimmed == "---" || trimmed == "***" -> {
                flushParagraph()
                blocks.add(MdBlock.Divider)
            }
            trimmed.startsWith("### ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading(3, trimmed.removePrefix("### ")))
            }
            trimmed.startsWith("## ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading(2, trimmed.removePrefix("## ")))
            }
            trimmed.startsWith("# ") -> {
                flushParagraph()
                blocks.add(MdBlock.Heading(1, trimmed.removePrefix("# ")))
            }
            trimmed.startsWith("> ") -> {
                flushParagraph()
                val quoteLines = mutableListOf(trimmed.removePrefix("> "))
                while (i + 1 < lines.size && lines[i + 1].trim().startsWith("> ")) {
                    i++
                    quoteLines.add(lines[i].trim().removePrefix("> "))
                }
                blocks.add(MdBlock.Quote(quoteLines.joinToString("\n")))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                blocks.add(MdBlock.BulletItem(trimmed.drop(2)))
            }
            trimmed.matches(Regex("^\\d+\\.\\s+.*")) -> {
                flushParagraph()
                val dotIndex = trimmed.indexOf('.')
                val num = trimmed.substring(0, dotIndex)
                val body = trimmed.substring(dotIndex + 1).trimStart()
                blocks.add(MdBlock.NumberedItem(num, body))
            }
            else -> paragraphBuffer.add(line)
        }
        i++
    }
    flushParagraph()
    return blocks
}

@Composable
private fun parseInlineMarkdown(text: String): AnnotatedString {
    val inlineCodeColor = MaterialTheme.colorScheme.secondary
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val mathColor = MaterialTheme.colorScheme.primary

    return buildAnnotatedString {
        var idx = 0
        while (idx < text.length) {
            when {
                // Inline LaTeX $...$
                text[idx] == '$' && !text.startsWith("$$", idx) -> {
                    val end = text.indexOf('$', idx + 1)
                    if (end != -1 && end > idx + 1) {
                        val latexExpr = text.substring(idx + 1, end)
                        withStyle(SpanStyle(color = mathColor)) {
                            append(formatLatexToAnnotated(latexExpr))
                        }
                        idx = end + 1
                    } else {
                        append('$')
                        idx++
                    }
                }
                // Inline LaTeX \(...\)
                text.startsWith("\\(", idx) -> {
                    val end = text.indexOf("\\)", idx + 2)
                    if (end != -1) {
                        val latexExpr = text.substring(idx + 2, end)
                        withStyle(SpanStyle(color = mathColor)) {
                            append(formatLatexToAnnotated(latexExpr))
                        }
                        idx = end + 2
                    } else {
                        append("\\(")
                        idx += 2
                    }
                }
                text.startsWith("**", idx) -> {
                    val end = text.indexOf("**", idx + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(idx + 2, end))
                        }
                        idx = end + 2
                    } else {
                        append("**")
                        idx += 2
                    }
                }
                text[idx] == '`' -> {
                    val end = text.indexOf('`', idx + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.5.sp,
                                color = inlineCodeColor,
                                background = inlineCodeBg
                            )
                        ) {
                            append(" ${text.substring(idx + 1, end)} ")
                        }
                        idx = end + 1
                    } else {
                        append('`')
                        idx++
                    }
                }
                text[idx] == '_' || text[idx] == '*' -> {
                    val marker = text[idx]
                    val end = text.indexOf(marker, idx + 1)
                    if (end != -1 && end > idx + 1 && !text[idx + 1].isWhitespace() && !text.substring(idx, end).contains('$')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(idx + 1, end))
                        }
                        idx = end + 1
                    } else {
                        append(marker)
                        idx++
                    }
                }
                else -> {
                    append(text[idx])
                    idx++
                }
            }
        }
    }
}
