package com.example.data.repository

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.local.MessageEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AuraPdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 40f
    private const val TOP_Y = 52f
    private const val BOTTOM_Y = 794f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2)

    fun exportMessageToPdf(
        context: Context,
        message: MessageEntity,
        title: String = "Aura AI Response"
    ): Uri {
        val file = createPdfFile(
            context = context,
            fileName = "aura_response_${System.currentTimeMillis()}.pdf",
            headerTitle = title,
            entries = listOf("Aura AI" to message.content)
        )
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportThreadToPdf(
        context: Context,
        messages: List<MessageEntity>,
        title: String = "Aura AI Conversation"
    ): Uri {
        val entries = messages
            .filter { !it.isError && it.content.isNotBlank() }
            .map { msg ->
                val speaker = if (msg.role == "user") "You" else "Aura AI"
                speaker to msg.content
            }
        val file = createPdfFile(
            context = context,
            fileName = "aura_thread_${System.currentTimeMillis()}.pdf",
            headerTitle = title,
            entries = entries
        )
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun exportCodeSnippetUri(
        context: Context,
        code: String,
        language: String,
        suggestedFilename: String? = null
    ): Pair<Uri, String> {
        val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val cleanName = buildSafeCodeFilename(language, suggestedFilename)
        val file = File(dir, cleanName)
        file.writeText(code, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to cleanName
    }

    fun exportHtmlFileUri(
        context: Context,
        htmlContent: String,
        suggestedTitle: String = "aura_artifact"
    ): Pair<Uri, String> {
        val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val base = suggestedTitle
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_')
            .take(42)
            .ifBlank { "aura_artifact" }
        val cleanName = if (base.endsWith(".html")) base else "${base}_${System.currentTimeMillis() % 10000}.html"
        val file = File(dir, cleanName)
        file.writeText(htmlContent, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return uri to cleanName
    }

    fun createPdfFile(
        context: Context,
        fileName: String,
        headerTitle: String,
        entries: List<Pair<String, String>>
    ): File {
        val dir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val outFile = File(dir, fileName)

        val document = PdfDocument()
        try {
            val headerBgPaint = Paint().apply { color = Color.parseColor("#10172A") }
            val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#7AA2FF")
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val speakerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1D4ED8")
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 12.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E293B")
                textSize = 10.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E2E8F0")
                textSize = 9.2f
                typeface = Typeface.MONOSPACE
            }
            val codeBgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 8.5f
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            var pageNumber = 1
            var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            var canvas = page.canvas

        fun drawBannerAndStartY(): Float {
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 66f, headerBgPaint)
            canvas.drawText("AURA AI · ABHYNEX", MARGIN_X, 24f, brandPaint)
            canvas.drawText(headerTitle.take(48), MARGIN_X, 46f, titlePaint)
            canvas.drawText("Exported $timestamp · Page $pageNumber", MARGIN_X, PAGE_HEIGHT - 20f, footerPaint)
            return 88f
        }

        fun nextPage(): Float {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            canvas.drawText("Aura AI · Abhynex · Page $pageNumber", MARGIN_X, PAGE_HEIGHT - 20f, footerPaint)
            return TOP_Y
        }

        var cursorY = drawBannerAndStartY()

        for ((speaker, rawContent) in entries) {
            if (cursorY + 28f > BOTTOM_Y) {
                cursorY = nextPage()
            }
            canvas.drawText(speaker, MARGIN_X, cursorY, speakerPaint)
            cursorY += 18f

            var inCodeBlock = false
            val lines = rawContent.replace("\r\n", "\n").lines()
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("```")) {
                    inCodeBlock = !inCodeBlock
                    cursorY += 4f
                    continue
                }
                if (trimmed.isEmpty()) {
                    cursorY += 8f
                    if (cursorY > BOTTOM_Y) cursorY = nextPage()
                    continue
                }

                val activePaint = when {
                    inCodeBlock -> codePaint
                    trimmed.startsWith("#") -> headingPaint
                    else -> bodyPaint
                }
                val cleanText = when {
                    inCodeBlock -> rawLine
                    trimmed.startsWith("### ") -> trimmed.removePrefix("### ")
                    trimmed.startsWith("## ") -> trimmed.removePrefix("## ")
                    trimmed.startsWith("# ") -> trimmed.removePrefix("# ")
                    else -> rawLine.replace("**", "").replace("`", "")
                }

                val maxLineWidth = if (inCodeBlock) CONTENT_WIDTH - 16f else CONTENT_WIDTH
                val wrappedLines = wrapText(cleanText, activePaint, maxLineWidth)
                for (wLine in wrappedLines) {
                    val lineHeight = if (inCodeBlock) 13.5f else 15.5f
                    if (cursorY + lineHeight > BOTTOM_Y) {
                        cursorY = nextPage()
                    }
                    if (inCodeBlock) {
                        canvas.drawRect(
                            MARGIN_X,
                            cursorY - 10.5f,
                            MARGIN_X + CONTENT_WIDTH,
                            cursorY + 3.5f,
                            codeBgPaint
                        )
                        canvas.drawText(wLine, MARGIN_X + 8f, cursorY, activePaint)
                    } else {
                        canvas.drawText(wLine, MARGIN_X, cursorY, activePaint)
                    }
                    cursorY += lineHeight
                }
            }
            cursorY += 14f
        }

            document.finishPage(page)
            FileOutputStream(outFile).use { fos ->
                document.writeTo(fos)
            }
            return outFile
        } finally {
            runCatching { document.close() }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val count = paint.breakText(text, start, text.length, true, maxWidth, null).coerceAtLeast(1)
            var end = start + count
            if (end < text.length) {
                val spaceIdx = text.lastIndexOf(' ', end - 1)
                if (spaceIdx > start + 8) {
                    end = spaceIdx + 1
                }
            }
            result += text.substring(start, end).trimEnd()
            start = end
        }
        return result
    }

    fun buildSafeCodeFilename(language: String, suggestedFilename: String?): String {
        val rawBase = suggestedFilename
            ?.substringAfterLast('/')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
        if (rawBase != null && rawBase.contains('.')) return rawBase

        val ext = when (language.trim().lowercase()) {
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "python", "py" -> "py"
            "javascript", "js" -> "js"
            "jsx" -> "jsx"
            "typescript", "ts" -> "ts"
            "tsx" -> "tsx"
            "html", "htm" -> "html"
            "css" -> "css"
            "svg" -> "svg"
            "json" -> "json"
            "xml" -> "xml"
            "sql" -> "sql"
            "bash", "sh", "shell" -> "sh"
            "yaml", "yml" -> "yml"
            "diff" -> "diff"
            else -> "txt"
        }
        return "${rawBase ?: "aura_snippet_${System.currentTimeMillis()}"}.$ext"
    }
}
