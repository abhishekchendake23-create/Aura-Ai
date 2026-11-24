package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.repository.AuraPdfExporter
import com.example.data.repository.ProjectZipExporter
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.delay

enum class ArtifactViewMode(val label: String) {
    PREVIEW("Preview"),
    SPLIT("Split"),
    CODE("Code")
}

fun isPreviewableArtifactLanguage(language: String): Boolean {
    val clean = language.trim().lowercase().substringBefore(':')
    return when (clean) {
        "html", "htm", "svg", "mermaid", "js", "javascript", "jsx", "react", "tailwind",
        "slides", "deck", "csv", "tsv" -> true
        else -> false
    }
}

private data class ConsoleEntry(
    val level: String,
    val message: String,
    val isError: Boolean
)

private class AuraArtifactConsoleBridge(
    private val onEntry: (ConsoleEntry) -> Unit
) {
    @JavascriptInterface
    fun postLog(level: String, message: String) {
        val cleanMsg = message.trim()
        if (cleanMsg.isBlank()) return
        val isErr = level.equals("ERROR", ignoreCase = true)
        onEntry(ConsoleEntry(level = level.uppercase(), message = cleanMsg, isError = isErr))
    }
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun ArtifactPreviewDialog(
    language: String,
    code: String,
    titleHint: String = "aura_artifact",
    onAutoFixError: ((errorLog: String, code: String) -> Unit)? = null,
    onIterateApp: ((instruction: String, currentCode: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var consoleExpanded by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(ArtifactViewMode.PREVIEW) }
    var editableCode by remember(code) { mutableStateOf(code) }
    var activeCode by remember(code) { mutableStateOf(code) }
    var iterateText by remember { mutableStateOf("") }
    val consoleEntries = remember(language, activeCode) { mutableStateListOf<ConsoleEntry>() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val htmlPayload = remember(language, activeCode) {
        buildArtifactHtml(language = language, code = activeCode)
    }
    val hasErrors = consoleEntries.any { it.isError }
    val errorSummary = remember(consoleEntries.size) {
        consoleEntries.filter { it.isError }.joinToString("\n") { it.message }
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
                .imePadding()
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close live artifact preview",
                            tint = auraColors.codeText
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = auraColors.emerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Aura Sandbox · ${language.substringBefore(':').uppercase()}",
                            style = MaterialTheme.typography.titleSmall,
                            color = auraColors.codeText
                        )
                        Text(
                            text = "Live Editable Runtime",
                            style = MaterialTheme.typography.labelSmall,
                            color = auraColors.emerald
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (editableCode != activeCode || viewMode != ArtifactViewMode.PREVIEW) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(auraColors.emeraldContainer)
                                .clickable {
                                    consoleEntries.clear()
                                    activeCode = editableCode
                                    if (viewMode == ArtifactViewMode.CODE) {
                                        viewMode = ArtifactViewMode.SPLIT
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("artifact_rerun_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Re-run modified code",
                                tint = auraColors.emerald,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Re-Run",
                                style = MaterialTheme.typography.labelMedium,
                                color = auraColors.emerald
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                runCatching {
                                    val uri = AuraPdfExporter.exportHtmlFileUri(
                                        context = context,
                                        htmlContent = htmlPayload,
                                        suggestedTitle = titleHint
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/html"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Aura AI · $titleHint")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Export .html Artifact"))
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("artifact_export_html_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Export standalone HTML file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = ".html",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(auraColors.emeraldContainer)
                            .clickable {
                                runCatching {
                                    val (uri, zipName) = ProjectZipExporter.exportAndroidApkProjectZipUri(
                                        context = context,
                                        appName = titleHint,
                                        htmlCode = htmlPayload
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "$titleHint Android APK Project ($zipName)")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Download Android APK Project (.zip)")
                                    )
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("artifact_export_apk_zip_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download Android APK Project ZIP",
                            tint = auraColors.emerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "APK",
                            style = MaterialTheme.typography.labelMedium,
                            color = auraColors.emerald
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                clipboard.setText(AnnotatedString(editableCode))
                                copied = true
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = "Copy artifact code",
                            tint = if (copied) auraColors.emerald else auraColors.codeText,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (copied) auraColors.emerald else auraColors.codeText
                        )
                    }
                }
            }

            // View Mode Switcher: Preview | Split | Code
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(auraColors.codeHeader.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ArtifactViewMode.entries.forEach { mode ->
                    val selected = mode == viewMode
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .border(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { viewMode = mode }
                            .padding(vertical = 7.dp)
                            .testTag("artifact_mode_${mode.name.lowercase()}"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (mode == ArtifactViewMode.CODE) Icons.Rounded.Code else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else auraColors.codeText.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp).padding(end = 2.dp)
                        )
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else auraColors.codeText
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (viewMode == ArtifactViewMode.PREVIEW || viewMode == ArtifactViewMode.SPLIT) {
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#0A0D14"))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            addJavascriptInterface(
                                AuraArtifactConsoleBridge { entry ->
                                    post {
                                        if (consoleEntries.size < 60) {
                                            consoleEntries.add(entry)
                                            if (entry.isError) consoleExpanded = true
                                        }
                                    }
                                },
                                "AuraConsoleBridge"
                            )
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    val msg = consoleMessage?.message()?.trim().orEmpty()
                                    if (msg.isNotBlank()) {
                                        val isErr = consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                                        val lvl = consoleMessage?.messageLevel()?.name ?: "LOG"
                                        post {
                                            if (consoleEntries.size < 60) {
                                                consoleEntries.add(ConsoleEntry(lvl, msg, isErr))
                                                if (isErr) consoleExpanded = true
                                            }
                                        }
                                    }
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val targetUrl = request?.url?.toString().orEmpty()
                                    return !targetUrl.startsWith("https://aura.local/")
                                }
                            }
                            loadDataWithBaseURL(
                                "https://aura.local/",
                                htmlPayload,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            "https://aura.local/",
                            htmlPayload,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                )
            }

            if (viewMode == ArtifactViewMode.SPLIT) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (viewMode == ArtifactViewMode.CODE || viewMode == ArtifactViewMode.SPLIT) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(auraColors.codeBackground)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    BasicTextField(
                        value = editableCode,
                        onValueChange = { editableCode = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = auraColors.codeText
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("artifact_code_editor")
                    )
                }
            }

            // Live Console & Error Auto-Fix Bar
            if (consoleEntries.isNotEmpty() || onAutoFixError != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (hasErrors) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            else auraColors.codeHeader
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { consoleExpanded = !consoleExpanded }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                            .testTag("artifact_console_toggle"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (hasErrors) Icons.Rounded.ErrorOutline else Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = if (hasErrors) MaterialTheme.colorScheme.error else auraColors.emerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when {
                                    hasErrors -> "Runtime Errors (${consoleEntries.count { it.isError }})"
                                    consoleEntries.isNotEmpty() -> "Console Output (${consoleEntries.size})"
                                    else -> "Runtime Console · Ready"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hasErrors) MaterialTheme.colorScheme.error else auraColors.codeText
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (hasErrors && onAutoFixError != null) {
                                Button(
                                    onClick = {
                                        onDismiss()
                                        onAutoFixError(errorSummary.ifBlank { "Runtime error inside artifact preview" }, activeCode)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("artifact_autofix_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoFixHigh,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp).size(15.dp)
                                    )
                                    Text("Auto-Fix Code", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (consoleEntries.isNotEmpty()) {
                                Icon(
                                    imageVector = if (consoleExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                    contentDescription = "Toggle console",
                                    tint = auraColors.codeText
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = consoleExpanded && consoleEntries.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .verticalScroll(rememberScrollState())
                                .background(auraColors.codeBackground)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            consoleEntries.forEach { entry ->
                                Text(
                                    text = "[${entry.level}] ${entry.message}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.5.sp,
                                    color = if (entry.isError) MaterialTheme.colorScheme.error else auraColors.codeText
                                )
                            }
                        }
                    }
                }
            }

            // Live App Modification Bar (AI Studio / Claude Artifacts Iteration)
            if (onIterateApp != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(auraColors.codeHeader)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        if (iterateText.isEmpty()) {
                            Text(
                                text = "Modify this app (e.g. add dark chart, sound, export)...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = iterateText,
                            onValueChange = { iterateText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("artifact_iterate_input")
                        )
                    }
                    val canSubmit = iterateText.isNotBlank()
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSubmit) auraColors.amber
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable(enabled = canSubmit) {
                                val cmd = iterateText.trim()
                                iterateText = ""
                                onDismiss()
                                onIterateApp(cmd, activeCode)
                            }
                            .testTag("artifact_iterate_send"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Apply modification to live app",
                            tint = if (canSubmit) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private const val CONSOLE_SHIM_SCRIPT = """
<script>
(function() {
  function send(level, args) {
    try {
      var msg = Array.prototype.slice.call(args).map(function(a) {
        if (typeof a === 'object') { try { return JSON.stringify(a); } catch(e) { return String(a); } }
        return String(a);
      }).join(' ');
      if (window.AuraConsoleBridge) window.AuraConsoleBridge.postLog(level, msg);
    } catch(e) {}
  }
  var origLog = console.log, origErr = console.error, origWarn = console.warn;
  console.log = function() { send('LOG', arguments); if (origLog) origLog.apply(console, arguments); };
  console.warn = function() { send('WARN', arguments); if (origWarn) origWarn.apply(console, arguments); };
  console.error = function() { send('ERROR', arguments); if (origErr) origErr.apply(console, arguments); };
  window.onerror = function(msg, src, line, col) {
    if (window.AuraConsoleBridge) {
      window.AuraConsoleBridge.postLog('ERROR', msg + ' (line ' + line + ':' + col + ')');
    }
  };
  window.addEventListener('unhandledrejection', function(ev) {
    if (window.AuraConsoleBridge) {
      window.AuraConsoleBridge.postLog('ERROR', 'Unhandled Promise: ' + (ev.reason && ev.reason.message ? ev.reason.message : ev.reason));
    }
  });
})();
</script>
"""

fun buildArtifactHtml(language: String, code: String): String {
    val lang = language.trim().lowercase().substringBefore(':')
    return when (lang) {
        "svg" -> """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                html, body { margin: 0; padding: 16px; background: #0A0D14; color: #E8EEFF; font-family: sans-serif; display: flex; justify-content: center; align-items: center; min-height: 90vh; }
                svg { max-width: 100%; height: auto; border-radius: 12px; }
              </style>
            </head>
            <body>
              $code
            </body>
            </html>
        """.trimIndent()

        "js", "javascript" -> {
            val escapedCode = code.replace("</script>", "<\\/script>")
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                html, body { margin: 0; padding: 16px; background: #0A0D14; color: #F0F4FF; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
                #app { margin-bottom: 14px; }
                canvas { max-width: 100%; border-radius: 12px; background: #101626; display: block; margin-bottom: 12px; }
                #output { background: #10141E; border: 1px solid #242E48; border-radius: 12px; padding: 12px; font-family: monospace; font-size: 13px; white-space: pre-wrap; color: #9EF5C8; }
              </style>
            </head>
            <body>
              <div id="app"></div>
              <canvas id="canvas" width="340" height="220" style="display:none"></canvas>
              <pre id="output">Running Aura JS Sandbox…</pre>
              <script>
                (function() {
                  const outEl = document.getElementById('output');
                  const lines = [];
                  const orig = console.log;
                  console.log = function() {
                    const txt = Array.from(arguments).map(x => typeof x === 'object' ? JSON.stringify(x, null, 2) : String(x)).join(' ');
                    lines.push(txt);
                    outEl.textContent = lines.join('\n');
                    orig.apply(console, arguments);
                  };
                  try {
                    $escapedCode
                    if (lines.length === 0) outEl.textContent = 'Executed cleanly (no console output).';
                  } catch (e) {
                    console.error(e.message || String(e));
                    outEl.style.color = '#FF8A8A';
                    outEl.textContent = 'Error: ' + (e.message || String(e));
                  }
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        "jsx", "react" -> {
            val escapedSource = org.json.JSONObject.quote(code)
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                html, body { margin: 0; padding: 16px; background: #0A0D14; color: #F0F4FF; font-family: -apple-system, sans-serif; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 10px; background: #162038; color: #7AA2FF; font-size: 12px; font-weight: 600; margin-bottom: 10px; }
                #root { background: #101626; border: 1px solid #242E48; border-radius: 14px; padding: 16px; min-height: 120px; }
                pre { background: #10141E; border: 1px solid #20263A; border-radius: 12px; padding: 12px; font-size: 12px; overflow-x: auto; color: #9BA7C2; margin-top: 12px; }
              </style>
            </head>
            <body>
              <div class="badge">Aura Component Sandbox</div>
              <div id="root"></div>
              <pre id="src"></pre>
              <script>
                (function() {
                  const raw = $escapedSource;
                  document.getElementById('src').textContent = raw;
                  const m = raw.match(/return\s*\(\s*([\s\S]*?)\s*\)\s*;?/);
                  const root = document.getElementById('root');
                  if (m && m[1]) {
                    const markup = m[1]
                      .replace(/className=/g, 'class=')
                      .replace(/\{([^{}]+)\}/g, '$1');
                    root.innerHTML = markup;
                  } else {
                    root.innerHTML = '<div style="color:#9BA7C2;font-size:13px;">JSX source rendered cleanly below. Ask Aura to output self-contained HTML for full interactive widgets.</div>';
                  }
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        "mermaid" -> {
            val escapedJson = org.json.JSONObject.quote(code)
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                html, body { margin: 0; padding: 16px; background: #0A0D14; color: #EAF0FF; font-family: -apple-system, sans-serif; }
                .wrap { display: flex; flex-direction: column; gap: 14px; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 10px; background: #162038; color: #7AA2FF; font-size: 12px; font-weight: 600; width: fit-content; }
                #canvas { width: 100%; overflow-x: auto; background: #0F1422; border: 1px solid #242E48; border-radius: 16px; padding: 16px; box-sizing: border-box; }
                pre { background: #10141E; border: 1px solid #20263A; border-radius: 12px; padding: 12px; font-size: 12px; overflow-x: auto; color: #9BA7C2; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="badge">Aura Offline Diagram Engine</div>
                <div id="canvas"></div>
                <pre id="src"></pre>
              </div>
              <script>
                (function() {
                  const src = $escapedJson;
                  document.getElementById('src').textContent = src;
                  const lines = src.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('%%'));
                  const nodes = new Map();
                  const edges = [];
                  const cleanLabel = (raw) => {
                    let id = raw.trim();
                    let label = id;
                    const m = id.match(/^([A-Za-z0-9_-]+)\s*[\[\(\{<]+([^\]\)\}>]+)[\]\)\}>]+/);
                    if (m) { id = m[1]; label = m[2].replace(/["']/g, ''); }
                    return { id, label };
                  };
                  lines.forEach(line => {
                    if (/^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram)/i.test(line)) return;
                    const arrowMatch = line.match(/^(.+?)\s*(?:--?>|==?>|-\.-?>|--\|([^|]+)\|->)\s*(.+)$/);
                    if (arrowMatch) {
                      const left = cleanLabel(arrowMatch[1]);
                      let edgeText = arrowMatch[2] || '';
                      let rightRaw = arrowMatch[3];
                      const pipeLabel = rightRaw.match(/^\|([^|]+)\|\s*(.+)$/);
                      if (pipeLabel) { edgeText = pipeLabel[1]; rightRaw = pipeLabel[2]; }
                      const right = cleanLabel(rightRaw);
                      if (!nodes.has(left.id)) nodes.set(left.id, left.label);
                      else if (left.label !== left.id) nodes.set(left.id, left.label);
                      if (!nodes.has(right.id)) nodes.set(right.id, right.label);
                      else if (right.label !== right.id) nodes.set(right.id, right.label);
                      edges.push({ from: left.id, to: right.id, text: edgeText });
                    }
                  });
                  const canvas = document.getElementById('canvas');
                  if (nodes.size === 0) {
                    canvas.innerHTML = '<div style="color:#9BA7C2;font-size:13px;">Diagram source displayed below.</div>';
                    return;
                  }
                  const nodeList = Array.from(nodes.entries());
                  const boxW = 220, boxH = 48, gapY = 44, padX = 24, padY = 20;
                  const svgW = Math.max(300, boxW + padX * 2);
                  const svgH = nodeList.length * (boxH + gapY) + padY;
                  const pos = new Map();
                  let svg = '<svg xmlns="http://www.w3.org/2000/svg" width="' + svgW + '" height="' + svgH + '" viewBox="0 0 ' + svgW + ' ' + svgH + '">';
                  svg += '<defs><marker id="arr" markerWidth="8" markerHeight="8" refX="7" refY="3.5" orient="auto"><polygon points="0 0, 8 3.5, 0 7" fill="#7AA2FF"/></marker></defs>';
                  nodeList.forEach(([id, label], idx) => {
                    const x = padX;
                    const y = padY + idx * (boxH + gapY);
                    pos.set(id, { x: x + boxW / 2, top: y, bottom: y + boxH });
                  });
                  edges.forEach((e, idx) => {
                    const a = pos.get(e.from), b = pos.get(e.to);
                    if (!a || !b) return;
                    const side = (idx % 2 === 0) ? 1 : -1;
                    const isAdjacent = Math.abs(b.top - a.bottom) <= gapY + 6;
                    if (isAdjacent && b.top > a.bottom) {
                      svg += '<line x1="' + a.x + '" y1="' + a.bottom + '" x2="' + b.x + '" y2="' + (b.top - 2) + '" stroke="#7AA2FF" stroke-width="2" marker-end="url(#arr)"/>';
                    } else {
                      const ctrlX = a.x + side * (boxW / 2 + 18);
                      svg += '<path d="M ' + a.x + ' ' + a.bottom + ' Q ' + ctrlX + ' ' + ((a.bottom + b.top)/2) + ' ' + b.x + ' ' + b.top + '" fill="none" stroke="#6CE5B1" stroke-width="1.8" marker-end="url(#arr)"/>';
                    }
                  });
                  nodeList.forEach(([id, label], idx) => {
                    const x = padX;
                    const y = padY + idx * (boxH + gapY);
                    const safeLabel = label.replace(/&/g,'&amp;').replace(/</g,'&lt;').slice(0, 32);
                    svg += '<rect x="' + x + '" y="' + y + '" width="' + boxW + '" height="' + boxH + '" rx="12" fill="#18223A" stroke="#4D7CFE" stroke-width="1.5"/>';
                    svg += '<text x="' + (x + boxW/2) + '" y="' + (y + boxH/2 + 5) + '" text-anchor="middle" fill="#F2F6FF" font-size="13" font-weight="600">' + safeLabel + '</text>';
                  });
                  svg += '</svg>';
                  canvas.innerHTML = svg;
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        "slides", "deck" -> {
            val escapedSlides = org.json.JSONObject.quote(code)
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; background: #070A10; color: #F2F6FF; font-family: -apple-system, BlinkMacSystemFont, sans-serif; min-height: 100vh; display: flex; flex-direction: column; }
                .topbar { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; border-bottom: 1px solid #1E2840; background: #0C111C; }
                .brand { font-size: 12px; font-weight: 700; letter-spacing: 0.08em; text-transform: uppercase; color: #7AA2FF; }
                .counter { font-size: 12px; font-weight: 600; color: #9EF5C8; background: #122823; padding: 4px 10px; border-radius: 999px; border: 1px solid #245B4D; }
                .stage { flex: 1; display: flex; align-items: center; justify-content: center; padding: 18px; }
                .card { width: 100%; max-width: 680px; min-height: 320px; background: linear-gradient(155deg, #101727 0%, #0B0F1A 100%); border: 1px solid #283556; border-radius: 22px; padding: 28px 24px; box-shadow: 0 20px 50px rgba(0,0,0,0.65); display: flex; flex-direction: column; justify-content: center; gap: 14px; }
                .card h1 { margin: 0 0 6px; font-size: 24px; line-height: 1.25; color: #FFFFFF; }
                .card h2 { margin: 0 0 4px; font-size: 19px; line-height: 1.3; color: #A6C2FF; }
                .card p { margin: 0; font-size: 15px; line-height: 1.55; color: #D4DEF2; }
                .card ul { margin: 4px 0 0; padding-left: 20px; display: flex; flex-direction: column; gap: 8px; }
                .card li { font-size: 15px; line-height: 1.5; color: #E2EBFF; }
                .card code { background: #18233C; color: #9EF5C8; padding: 2px 6px; border-radius: 6px; font-family: monospace; font-size: 13px; }
                .controls { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 18px; border-top: 1px solid #1E2840; background: #0C111C; }
                button { background: #1A2540; color: #F2F6FF; border: 1px solid #354B7D; border-radius: 12px; padding: 10px 18px; font-size: 14px; font-weight: 600; cursor: pointer; }
                button:disabled { opacity: 0.35; cursor: default; }
                .dots { display: flex; gap: 6px; flex-wrap: wrap; justify-content: center; }
                .dot { width: 8px; height: 8px; border-radius: 50%; background: #2A3754; cursor: pointer; }
                .dot.active { background: #7AA2FF; transform: scale(1.2); }
              </style>
            </head>
            <body>
              <div class="topbar">
                <div class="brand">Aura Deck Studio</div>
                <div class="counter" id="counter">Slide 1 / 1</div>
              </div>
              <div class="stage">
                <div class="card" id="slideCard"></div>
              </div>
              <div class="controls">
                <button id="prevBtn">← Prev</button>
                <div class="dots" id="dots"></div>
                <button id="nextBtn">Next →</button>
              </div>
              <script>
                (function() {
                  const raw = $escapedSlides;
                  const chunks = raw.split(/^\s*---\s*$/m).map(s => s.trim()).filter(Boolean);
                  const slides = chunks.length ? chunks : [raw];
                  let idx = 0;
                  function esc(s) {
                    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
                  }
                  function fmtInline(s) {
                    return esc(s)
                      .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
                      .replace(/`([^`]+)`/g, '<code>$1</code>');
                  }
                  function renderSlide(text) {
                    const lines = text.split('\n');
                    let html = '';
                    let inList = false;
                    for (const lineRaw of lines) {
                      const line = lineRaw.trim();
                      if (!line) {
                        if (inList) { html += '</ul>'; inList = false; }
                        continue;
                      }
                      if (/^#\s+/.test(line)) {
                        if (inList) { html += '</ul>'; inList = false; }
                        html += '<h1>' + fmtInline(line.replace(/^#\s+/, '')) + '</h1>';
                      } else if (/^#{2,3}\s+/.test(line)) {
                        if (inList) { html += '</ul>'; inList = false; }
                        html += '<h2>' + fmtInline(line.replace(/^#{2,3}\s+/, '')) + '</h2>';
                      } else if (/^[-*•]\s+/.test(line)) {
                        if (!inList) { html += '<ul>'; inList = true; }
                        html += '<li>' + fmtInline(line.replace(/^[-*•]\s+/, '')) + '</li>';
                      } else {
                        if (inList) { html += '</ul>'; inList = false; }
                        html += '<p>' + fmtInline(line) + '</p>';
                      }
                    }
                    if (inList) html += '</ul>';
                    return html;
                  }
                  function update() {
                    document.getElementById('slideCard').innerHTML = renderSlide(slides[idx]);
                    document.getElementById('counter').textContent = 'Slide ' + (idx + 1) + ' / ' + slides.length;
                    document.getElementById('prevBtn').disabled = idx === 0;
                    document.getElementById('nextBtn').disabled = idx >= slides.length - 1;
                    const dotsEl = document.getElementById('dots');
                    dotsEl.innerHTML = '';
                    slides.forEach((_, i) => {
                      const d = document.createElement('div');
                      d.className = 'dot' + (i === idx ? ' active' : '');
                      d.onclick = () => { idx = i; update(); };
                      dotsEl.appendChild(d);
                    });
                  }
                  document.getElementById('prevBtn').onclick = () => { if (idx > 0) { idx--; update(); } };
                  document.getElementById('nextBtn').onclick = () => { if (idx < slides.length - 1) { idx++; update(); } };
                  document.addEventListener('keydown', (e) => {
                    if (e.key === 'ArrowRight' && idx < slides.length - 1) { idx++; update(); }
                    if (e.key === 'ArrowLeft' && idx > 0) { idx--; update(); }
                  });
                  update();
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        "csv", "tsv" -> {
            val escapedData = org.json.JSONObject.quote(code)
            val isTsv = lang == "tsv"
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              $CONSOLE_SHIM_SCRIPT
              <style>
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 14px; background: #070A10; color: #EDF2FF; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 10px; background: #14223B; color: #7AA2FF; font-size: 12px; font-weight: 700; margin-bottom: 12px; }
                .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; margin-bottom: 14px; }
                .metric { background: #101625; border: 1px solid #24304D; border-radius: 12px; padding: 10px 12px; }
                .metric .k { font-size: 11px; color: #8A99BA; text-transform: uppercase; }
                .metric .v { font-size: 16px; font-weight: 700; color: #9EF5C8; margin-top: 2px; }
                .chartBox { background: #0E1422; border: 1px solid #24304D; border-radius: 14px; padding: 12px; margin-bottom: 14px; overflow-x: auto; }
                .tableWrap { background: #0E1422; border: 1px solid #24304D; border-radius: 14px; overflow-x: auto; }
                table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
                th { background: #162038; color: #A6C2FF; text-align: left; padding: 10px 12px; cursor: pointer; user-select: none; border-bottom: 1px solid #2A385C; white-space: nowrap; }
                td { padding: 8px 12px; border-bottom: 1px solid #1A233A; color: #E2EAFF; white-space: nowrap; }
                tr:hover td { background: #121B30; }
              </style>
            </head>
            <body>
              <div class="badge">Aura Dataset Visualizer · ${if (isTsv) "TSV" else "CSV"}</div>
              <div class="metrics" id="metrics"></div>
              <div class="chartBox" id="chartBox"></div>
              <div class="tableWrap" id="tableWrap"></div>
              <script>
                (function() {
                  const raw = $escapedData;
                  const delim = ${if (isTsv) "'\\t'" else "','"};
                  function parseDelimited(text, d) {
                    const rows = [];
                    let row = [], cell = '', inQ = false;
                    for (let i = 0; i < text.length; i++) {
                      const ch = text[i];
                      if (ch === '"') {
                        if (inQ && text[i+1] === '"') { cell += '"'; i++; }
                        else inQ = !inQ;
                      } else if (ch === d && !inQ) {
                        row.push(cell.trim()); cell = '';
                      } else if ((ch === '\n' || ch === '\r') && !inQ) {
                        if (ch === '\r' && text[i+1] === '\n') i++;
                        row.push(cell.trim());
                        if (row.some(c => c.length > 0)) rows.push(row);
                        row = []; cell = '';
                      } else {
                        cell += ch;
                      }
                    }
                    if (cell.length > 0 || row.length > 0) {
                      row.push(cell.trim());
                      if (row.some(c => c.length > 0)) rows.push(row);
                    }
                    return rows;
                  }
                  function esc(s) { return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;'); }
                  const parsed = parseDelimited(raw, delim);
                  if (parsed.length === 0) {
                    document.getElementById('tableWrap').innerHTML = '<div style="padding:14px;color:#9BA7C2">Empty dataset</div>';
                    return;
                  }
                  const headers = parsed[0];
                  let dataRows = parsed.slice(1);
                  let sortCol = -1, sortAsc = true;
                  let numCol = -1;
                  for (let c = 0; c < headers.length; c++) {
                    const nums = dataRows.map(r => Number(String(r[c] || '').replace(/[$,%]/g, ''))).filter(n => !Number.isNaN(n));
                    if (nums.length > 0 && nums.length >= dataRows.length * 0.5) { numCol = c; break; }
                  }
                  function renderMetrics() {
                    let html = '<div class="metric"><div class="k">Rows</div><div class="v">' + dataRows.length + '</div></div>';
                    html += '<div class="metric"><div class="k">Columns</div><div class="v">' + headers.length + '</div></div>';
                    if (numCol >= 0) {
                      const vals = dataRows.map(r => Number(String(r[numCol] || '').replace(/[$,%]/g, ''))).filter(n => !Number.isNaN(n));
                      if (vals.length) {
                        const sum = vals.reduce((a,b) => a + b, 0);
                        const avg = (sum / vals.length).toFixed(2);
                        const maxV = Math.max(...vals);
                        html += '<div class="metric"><div class="k">' + esc(headers[numCol]) + ' Avg</div><div class="v">' + avg + '</div></div>';
                        html += '<div class="metric"><div class="k">' + esc(headers[numCol]) + ' Max</div><div class="v">' + maxV + '</div></div>';
                      }
                    }
                    document.getElementById('metrics').innerHTML = html;
                  }
                  function renderChart() {
                    const box = document.getElementById('chartBox');
                    if (numCol < 0 || dataRows.length === 0) {
                      box.style.display = 'none';
                      return;
                    }
                    const items = dataRows.slice(0, 14).map((r, i) => ({
                      label: String(r[0] || ('Row ' + (i+1))).slice(0, 12),
                      val: Number(String(r[numCol] || '0').replace(/[$,%]/g, '')) || 0
                    }));
                    const maxV = Math.max(1, ...items.map(x => Math.abs(x.val)));
                    const barW = 28, gap = 12, padL = 16, h = 150, w = Math.max(300, padL * 2 + items.length * (barW + gap));
                    let svg = '<div style="font-size:12px;color:#A6C2FF;font-weight:600;margin-bottom:8px;">Bar Chart · ' + esc(headers[numCol]) + '</div>';
                    svg += '<svg width="' + w + '" height="' + (h + 32) + '" viewBox="0 0 ' + w + ' ' + (h + 32) + '">';
                    items.forEach((it, i) => {
                      const bh = Math.max(4, Math.round((Math.abs(it.val) / maxV) * (h - 24)));
                      const x = padL + i * (barW + gap);
                      const y = h - bh;
                      svg += '<rect x="' + x + '" y="' + y + '" width="' + barW + '" height="' + bh + '" rx="6" fill="#5D87FF"/>';
                      svg += '<text x="' + (x + barW/2) + '" y="' + (y - 4) + '" text-anchor="middle" fill="#9EF5C8" font-size="9">' + it.val + '</text>';
                      svg += '<text x="' + (x + barW/2) + '" y="' + (h + 16) + '" text-anchor="middle" fill="#9BA7C2" font-size="9">' + esc(it.label) + '</text>';
                    });
                    svg += '</svg>';
                    box.innerHTML = svg;
                  }
                  function renderTable() {
                    let t = '<table><thead><tr>';
                    headers.forEach((h, i) => {
                      const ind = sortCol === i ? (sortAsc ? ' ↑' : ' ↓') : '';
                      t += '<th data-col="' + i + '">' + esc(h) + ind + '</th>';
                    });
                    t += '</tr></thead><tbody>';
                    dataRows.forEach(r => {
                      t += '<tr>';
                      for (let i = 0; i < headers.length; i++) t += '<td>' + esc(r[i] || '') + '</td>';
                      t += '</tr>';
                    });
                    t += '</tbody></table>';
                    const wrap = document.getElementById('tableWrap');
                    wrap.innerHTML = t;
                    wrap.querySelectorAll('th').forEach(th => {
                      th.onclick = () => {
                        const c = Number(th.getAttribute('data-col'));
                        if (sortCol === c) sortAsc = !sortAsc; else { sortCol = c; sortAsc = true; }
                        dataRows.sort((a, b) => {
                          const av = a[c] || '', bv = b[c] || '';
                          const an = Number(av.replace(/[$,%]/g, '')), bn = Number(bv.replace(/[$,%]/g, ''));
                          if (!Number.isNaN(an) && !Number.isNaN(bn) && av !== '' && bv !== '') {
                            return sortAsc ? an - bn : bn - an;
                          }
                          return sortAsc ? av.localeCompare(bv) : bv.localeCompare(av);
                        });
                        renderTable();
                      };
                    });
                  }
                  renderMetrics();
                  renderChart();
                  renderTable();
                })();
              </script>
            </body>
            </html>
            """.trimIndent()
        }

        else -> {
            val trimmed = code.trim()
            if (trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!doctype", ignoreCase = true)) {
                if (trimmed.contains("<head>", ignoreCase = true)) {
                    trimmed.replaceFirst("<head>", "<head>$CONSOLE_SHIM_SCRIPT", ignoreCase = true)
                } else {
                    "$CONSOLE_SHIM_SCRIPT$trimmed"
                }
            } else {
                """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  $CONSOLE_SHIM_SCRIPT
                  <style>
                    html, body { margin: 0; padding: 14px; background: #0A0D14; color: #F0F4FF; font-family: -apple-system, BlinkMacSystemFont, sans-serif; }
                    * { box-sizing: border-box; }
                  </style>
                </head>
                <body>
                  $trimmed
                </body>
                </html>
                """.trimIndent()
            }
        }
    }
}
