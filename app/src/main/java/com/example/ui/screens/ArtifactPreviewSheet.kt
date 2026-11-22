package com.example.ui.screens

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.delay

fun isPreviewableArtifactLanguage(language: String): Boolean {
    val clean = language.trim().lowercase().substringBefore(':')
    return when (clean) {
        "html", "htm", "svg", "mermaid", "js", "javascript", "jsx", "react", "tailwind" -> true
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
    onAutoFixError: ((errorLog: String, code: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var consoleExpanded by remember { mutableStateOf(false) }
    val consoleEntries = remember(language, code) { mutableStateListOf<ConsoleEntry>() }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val htmlPayload = remember(language, code) {
        buildArtifactHtml(language = language, code = code)
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
                            text = "Aura Live Artifact · ${language.substringBefore(':').uppercase()}",
                            style = MaterialTheme.typography.titleSmall,
                            color = auraColors.codeText
                        )
                        Text(
                            text = "Sandboxed Interactive Runtime",
                            style = MaterialTheme.typography.labelSmall,
                            color = auraColors.emerald
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
                        contentDescription = "Copy artifact code",
                        tint = if (copied) auraColors.emerald else auraColors.codeText,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (copied) auraColors.emerald else auraColors.codeText
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                                        onAutoFixError(errorSummary.ifBlank { "Runtime error inside artifact preview" }, code)
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
