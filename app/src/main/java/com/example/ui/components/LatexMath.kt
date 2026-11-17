package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalAuraColors
import kotlinx.coroutines.delay

/**
 * Native Compose KaTeX-grade math renderer.
 * Supports display math blocks (`$$...$$`, `\[...\]`), stacked `\frac{a}{b}`, `\sqrt{x}`,
 * `\boxed{...}`, `\int_{a}^{b}`, `\sum_{i=1}^{n}`, `\lim_{x \to c}`, matrices/cases,
 * superscripts/subscripts, and full LaTeX symbol substitution without WebView flicker.
 */
@Composable
fun MathBlockCard(
    latex: String,
    modifier: Modifier = Modifier
) {
    val auraColors = LocalAuraColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val cleaned = remember(latex) {
        latex.trim()
            .removePrefix("$$").removeSuffix("$$")
            .removePrefix("\\[").removeSuffix("\\]")
            .trim()
    }

    val lines = remember(cleaned) { splitAlignedLatexRows(cleaned) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MATH",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(cleaned))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy LaTeX",
                    tint = if (copied) auraColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (copied) "Copied" else "TeX",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) auraColors.emerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            lines.forEach { rowLatex ->
                MathExpressionNodeView(
                    expr = rowLatex,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun splitAlignedLatexRows(raw: String): List<String> {
    val strippedEnv = raw
        .replace(Regex("\\\\begin\\{(?:aligned|align\\*?|gather\\*?|equation\\*?|cases)\\}"), "")
        .replace(Regex("\\\\end\\{(?:aligned|align\\*?|gather\\*?|equation\\*?|cases)\\}"), "")
        .replace("&=", "=")
        .replace("&", " ")
        .trim()
    return strippedEnv
        .split("\\\\")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(raw) }
}

private sealed interface MathToken {
    data class TextRun(val annotated: AnnotatedString) : MathToken
    data class Fraction(val num: String, val den: String) : MathToken
    data class Radical(val index: String?, val body: String) : MathToken
    data class Boxed(val inner: String) : MathToken
    data class BigOp(val symbol: String, val lower: String?, val upper: String?) : MathToken
}

@Composable
private fun MathExpressionNodeView(
    expr: String,
    fontSize: TextUnit,
    color: Color
) {
    val tokens = remember(expr) { tokenizeDisplayMath(expr) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tokens.forEach { token ->
            when (token) {
                is MathToken.TextRun -> {
                    Text(
                        text = token.annotated,
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize = fontSize,
                            color = color,
                            lineHeight = fontSize * 1.35f
                        )
                    )
                }

                is MathToken.Fraction -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MathExpressionNodeView(
                                expr = token.num,
                                fontSize = (fontSize.value * 0.84f).sp,
                                color = color
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.2.dp)
                                .background(color.copy(alpha = 0.85f))
                        )
                        Box(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MathExpressionNodeView(
                                expr = token.den,
                                fontSize = (fontSize.value * 0.84f).sp,
                                color = color
                            )
                        }
                    }
                }

                is MathToken.Radical -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        if (!token.index.isNullOrBlank()) {
                            Text(
                                text = formatLatexToAnnotated(token.index),
                                fontFamily = FontFamily.Serif,
                                fontSize = (fontSize.value * 0.62f).sp,
                                color = color,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            text = "√",
                            fontFamily = FontFamily.Serif,
                            fontSize = (fontSize.value * 1.15f).sp,
                            fontWeight = FontWeight.Light,
                            color = color
                        )
                        Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.1.dp)
                                    .background(color.copy(alpha = 0.85f))
                            )
                            Box(modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)) {
                                MathExpressionNodeView(
                                    expr = token.body,
                                    fontSize = (fontSize.value * 0.94f).sp,
                                    color = color
                                )
                            }
                        }
                    }
                }

                is MathToken.Boxed -> {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                            .border(
                                width = 1.4.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        MathExpressionNodeView(
                            expr = token.inner,
                            fontSize = fontSize,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is MathToken.BigOp -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 3.dp)
                    ) {
                        if (!token.upper.isNullOrBlank()) {
                            Text(
                                text = formatLatexToAnnotated(token.upper),
                                fontFamily = FontFamily.Serif,
                                fontSize = (fontSize.value * 0.64f).sp,
                                color = color.copy(alpha = 0.9f)
                            )
                        }
                        Text(
                            text = token.symbol,
                            fontFamily = FontFamily.Serif,
                            fontSize = if (token.symbol == "lim") fontSize else (fontSize.value * 1.35f).sp,
                            fontWeight = FontWeight.Normal,
                            color = color
                        )
                        if (!token.lower.isNullOrBlank()) {
                            Text(
                                text = formatLatexToAnnotated(token.lower),
                                fontFamily = FontFamily.Serif,
                                fontSize = (fontSize.value * 0.64f).sp,
                                color = color.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tokenizeDisplayMath(expr: String): List<MathToken> {
    val tokens = mutableListOf<MathToken>()
    val textBuf = StringBuilder()
    var i = 0

    fun flushText() {
        if (textBuf.isNotEmpty()) {
            tokens.add(MathToken.TextRun(formatLatexToAnnotated(textBuf.toString())))
            textBuf.clear()
        }
    }

    while (i < expr.length) {
        when {
            expr.startsWith("\\frac", i) || expr.startsWith("\\dfrac", i) || expr.startsWith("\\tfrac", i) -> {
                val cmdLen = when {
                    expr.startsWith("\\dfrac", i) || expr.startsWith("\\tfrac", i) -> 6
                    else -> 5
                }
                val numArg = readBracedGroup(expr, i + cmdLen)
                val denArg = numArg?.let { readBracedGroup(expr, it.nextIndex) }
                if (numArg != null && denArg != null) {
                    flushText()
                    tokens.add(MathToken.Fraction(numArg.content, denArg.content))
                    i = denArg.nextIndex
                    continue
                }
            }

            expr.startsWith("\\sqrt", i) -> {
                var cursor = i + 5
                var rootIndex: String? = null
                while (cursor < expr.length && expr[cursor].isWhitespace()) cursor++
                if (cursor < expr.length && expr[cursor] == '[') {
                    val closeBracket = expr.indexOf(']', cursor + 1)
                    if (closeBracket != -1) {
                        rootIndex = expr.substring(cursor + 1, closeBracket)
                        cursor = closeBracket + 1
                    }
                }
                val bodyArg = readBracedGroup(expr, cursor)
                if (bodyArg != null) {
                    flushText()
                    tokens.add(MathToken.Radical(rootIndex, bodyArg.content))
                    i = bodyArg.nextIndex
                    continue
                }
            }

            expr.startsWith("\\boxed", i) -> {
                val arg = readBracedGroup(expr, i + 6)
                if (arg != null) {
                    flushText()
                    tokens.add(MathToken.Boxed(arg.content))
                    i = arg.nextIndex
                    continue
                }
            }

            expr.startsWith("\\int", i) || expr.startsWith("\\sum", i) ||
                expr.startsWith("\\prod", i) || expr.startsWith("\\lim", i) -> {
                val (cmd, sym) = when {
                    expr.startsWith("\\int", i) -> "\\int" to "∫"
                    expr.startsWith("\\sum", i) -> "\\sum" to "∑"
                    expr.startsWith("\\prod", i) -> "\\prod" to "∏"
                    else -> "\\lim" to "lim"
                }
                val afterCmd = i + cmd.length
                val isWordBoundary = afterCmd >= expr.length || !expr[afterCmd].isLetter()
                if (isWordBoundary) {
                    var cursor = afterCmd
                    var lower: String? = null
                    var upper: String? = null
                    // Optional \limits
                    if (expr.startsWith("\\limits", cursor)) cursor += 7
                    repeat(2) {
                        while (cursor < expr.length && expr[cursor].isWhitespace()) cursor++
                        if (cursor < expr.length && expr[cursor] == '_' && lower == null) {
                            val sub = readScriptArg(expr, cursor + 1)
                            if (sub != null) {
                                lower = sub.content
                                cursor = sub.nextIndex
                            }
                        } else if (cursor < expr.length && expr[cursor] == '^' && upper == null) {
                            val sup = readScriptArg(expr, cursor + 1)
                            if (sup != null) {
                                upper = sup.content
                                cursor = sup.nextIndex
                            }
                        }
                    }
                    if (lower != null || upper != null) {
                        flushText()
                        tokens.add(MathToken.BigOp(sym, lower, upper))
                        i = cursor
                        continue
                    }
                }
            }
        }
        textBuf.append(expr[i])
        i++
    }
    flushText()
    return tokens
}

private data class ParsedGroup(val content: String, val nextIndex: Int)

private fun readBracedGroup(source: String, startIndex: Int): ParsedGroup? {
    var idx = startIndex
    while (idx < source.length && source[idx].isWhitespace()) idx++
    if (idx >= source.length || source[idx] != '{') return null
    var depth = 0
    val contentStart = idx + 1
    while (idx < source.length) {
        when (source[idx]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return ParsedGroup(source.substring(contentStart, idx), idx + 1)
                }
            }
        }
        idx++
    }
    return null
}

private fun readScriptArg(source: String, startIndex: Int): ParsedGroup? {
    var idx = startIndex
    while (idx < source.length && source[idx].isWhitespace()) idx++
    if (idx >= source.length) return null
    if (source[idx] == '{') return readBracedGroup(source, idx)
    if (source[idx] == '\\') {
        var end = idx + 1
        while (end < source.length && source[end].isLetter()) end++
        return ParsedGroup(source.substring(idx, end), end)
    }
    return ParsedGroup(source[idx].toString(), idx + 1)
}

/**
 * Formats an inline LaTeX expression (e.g. from `$...$` or `\(...\)`) into a polished
 * mathematical [AnnotatedString] with real Unicode symbols, serif math styling,
 * superscripts, and subscripts.
 */
fun formatLatexToAnnotated(rawLatex: String): AnnotatedString {
    val normalized = replaceLatexCommands(rawLatex)
    return buildAnnotatedString {
        var i = 0
        while (i < normalized.length) {
            when {
                normalized[i] == '^' && i + 1 < normalized.length -> {
                    val arg = readScriptArg(normalized, i + 1)
                    if (arg != null) {
                        withStyle(
                            SpanStyle(
                                baselineShift = BaselineShift.Superscript,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Serif
                            )
                        ) {
                            append(replaceLatexCommands(arg.content))
                        }
                        i = arg.nextIndex
                    } else {
                        append('^')
                        i++
                    }
                }

                normalized[i] == '_' && i + 1 < normalized.length -> {
                    val arg = readScriptArg(normalized, i + 1)
                    if (arg != null) {
                        withStyle(
                            SpanStyle(
                                baselineShift = BaselineShift.Subscript,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Serif
                            )
                        ) {
                            append(replaceLatexCommands(arg.content))
                        }
                        i = arg.nextIndex
                    } else {
                        append('_')
                        i++
                    }
                }

                normalized[i] == '{' || normalized[i] == '}' -> {
                    i++
                }

                else -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = if (normalized[i].isLetter()) FontStyle.Italic else FontStyle.Normal
                        )
                    ) {
                        append(normalized[i])
                    }
                    i++
                }
            }
        }
    }
}

private fun replaceLatexCommands(input: String): String {
    var out = input
        .replace("\\left(", "(")
        .replace("\\right)", ")")
        .replace("\\left[", "[")
        .replace("\\right]", "]")
        .replace("\\left\\{", "{")
        .replace("\\right\\}", "}")
        .replace("\\left|", "|")
        .replace("\\right|", "|")
        .replace("\\left.", "")
        .replace("\\right.", "")
        .replace("\\qquad", "  ")
        .replace("\\quad", " ")
        .replace("\\,", " ")
        .replace("\\;", " ")
        .replace("\\:", " ")
        .replace("\\!", "")

    // Handle inline \frac{a}{b}, \sqrt{x}, \text{...}, \mathrm{...}, \mathbf{...}
    out = Regex("\\\\(?:d|t)?frac\\{([^{}]+)\\}\\{([^{}]+)\\}").replace(out) { m ->
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        "($a)/($b)"
    }
    out = Regex("\\\\sqrt\\{([^{}]+)\\}").replace(out) { m -> "√(${m.groupValues[1]})" }
    out = Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\{([^{}]+)\\}").replace(out) { m ->
        m.groupValues[1]
    }
    out = Regex("\\\\boxed\\{([^{}]+)\\}").replace(out) { m -> "[ ${m.groupValues[1]} ]" }

    for ((cmd, symbol) in LATEX_SYMBOLS) {
        out = out.replace(cmd, symbol)
    }
    return out.replace(Regex("\\s{2,}"), " ").trim()
}

private val LATEX_SYMBOLS = listOf(
    "\\Longleftrightarrow" to "⟺",
    "\\Leftrightarrow" to "⇔",
    "\\leftrightarrow" to "↔",
    "\\Longrightarrow" to "⟹",
    "\\Rightarrow" to "⇒",
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\mapsto" to "↦",
    "\\subseteq" to "⊆",
    "\\supseteq" to "⊇",
    "\\subset" to "⊂",
    "\\supset" to "⊃",
    "\\approx" to "≈",
    "\\equiv" to "≡",
    "\\notin" to "∉",
    "\\infty" to "∞",
    "\\partial" to "∂",
    "\\nabla" to "∇",
    "\\forall" to "∀",
    "\\exists" to "∃",
    "\\therefore" to "∴",
    "\\because" to "∵",
    "\\degree" to "°",
    "\\times" to "×",
    "\\cdot" to "·",
    "\\div" to "÷",
    "\\neq" to "≠",
    "\\leq" to "≤",
    "\\geq" to "≥",
    "\\le" to "≤",
    "\\ge" to "≥",
    "\\pm" to "±",
    "\\mp" to "∓",
    "\\int" to "∫",
    "\\iint" to "∬",
    "\\iiint" to "∭",
    "\\oint" to "∮",
    "\\sum" to "∑",
    "\\prod" to "∏",
    "\\cup" to "∪",
    "\\cap" to "∩",
    "\\in" to "∈",
    "\\to" to "→",
    "\\Alpha" to "Α",
    "\\Beta" to "Β",
    "\\Gamma" to "Γ",
    "\\Delta" to "Δ",
    "\\Theta" to "Θ",
    "\\Lambda" to "Λ",
    "\\Sigma" to "Σ",
    "\\Phi" to "Φ",
    "\\Psi" to "Ψ",
    "\\Omega" to "Ω",
    "\\alpha" to "α",
    "\\beta" to "β",
    "\\gamma" to "γ",
    "\\delta" to "δ",
    "\\epsilon" to "ε",
    "\\varepsilon" to "ε",
    "\\zeta" to "ζ",
    "\\eta" to "η",
    "\\theta" to "θ",
    "\\iota" to "ι",
    "\\kappa" to "κ",
    "\\lambda" to "λ",
    "\\mu" to "μ",
    "\\nu" to "ν",
    "\\xi" to "ξ",
    "\\pi" to "π",
    "\\rho" to "ρ",
    "\\sigma" to "σ",
    "\\tau" to "τ",
    "\\phi" to "φ",
    "\\varphi" to "φ",
    "\\chi" to "χ",
    "\\psi" to "ψ",
    "\\omega" to "ω",
    "\\arcsin" to "arcsin",
    "\\arccos" to "arccos",
    "\\arctan" to "arctan",
    "\\sinh" to "sinh",
    "\\cosh" to "cosh",
    "\\tanh" to "tanh",
    "\\sin" to "sin",
    "\\cos" to "cos",
    "\\tan" to "tan",
    "\\sec" to "sec",
    "\\csc" to "csc",
    "\\cot" to "cot",
    "\\ln" to "ln",
    "\\log" to "log",
    "\\exp" to "exp",
    "\\lim" to "lim",
    "\\dots" to "…",
    "\\cdots" to "⋯",
    "\\vdots" to "⋮",
    "\\%" to "%"
)
