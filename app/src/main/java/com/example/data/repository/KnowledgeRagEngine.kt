package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.local.KnowledgeChunkEntity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.ln

data class RenderedScanPage(
    val uriString: String,
    val label: String
)

object KnowledgeRagEngine {

    private val STOP_WORDS = setOf(
        "the", "and", "for", "that", "this", "with", "from", "are", "was", "were",
        "have", "has", "had", "not", "but", "what", "when", "where", "which", "who",
        "will", "would", "could", "should", "into", "about", "your", "you", "our",
        "can", "all", "any", "use", "using", "how", "why", "its", "their", "them",
        "been", "being", "than", "then", "also", "more", "some", "such", "only"
    )

    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }

    fun chunkDocument(
        docId: String,
        docTitle: String,
        rawText: String,
        chunkSizeChars: Int = 850,
        overlapChars: Int = 160
    ): List<KnowledgeChunkEntity> {
        val cleaned = rawText
            .replace("\r\n", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (cleaned.isBlank()) return emptyList()
        if (cleaned.length <= chunkSizeChars) {
            return listOf(
                KnowledgeChunkEntity(
                    id = "${docId}_chunk_0",
                    docId = docId,
                    docTitle = docTitle,
                    chunkIndex = 0,
                    content = cleaned
                )
            )
        }

        val chunks = mutableListOf<KnowledgeChunkEntity>()
        val step = (chunkSizeChars - overlapChars).coerceAtLeast(180)
        var start = 0
        var index = 0
        while (start < cleaned.length) {
            var end = (start + chunkSizeChars).coerceAtMost(cleaned.length)
            if (end < cleaned.length) {
                val boundary = cleaned.lastIndexOf('\n', end).takeIf { it > start + step / 2 }
                    ?: cleaned.lastIndexOf(' ', end).takeIf { it > start + step / 2 }
                if (boundary != null) {
                    end = boundary
                }
            }
            val slice = cleaned.substring(start, end).trim()
            if (slice.isNotEmpty()) {
                chunks.add(
                    KnowledgeChunkEntity(
                        id = "${docId}_chunk_$index",
                        docId = docId,
                        docTitle = docTitle,
                        chunkIndex = index,
                        content = slice
                    )
                )
                index++
            }
            if (end >= cleaned.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
        }
        return chunks
    }

    fun retrieveTopChunks(
        query: String,
        allChunks: List<KnowledgeChunkEntity>,
        topK: Int = 4
    ): List<KnowledgeChunkEntity> {
        if (allChunks.isEmpty() || query.isBlank()) return emptyList()
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return allChunks.take(topK)

        val docFreq = mutableMapOf<String, Int>()
        val tokenizedDocs = allChunks.map { chunk ->
            val tokens = tokenize("${chunk.docTitle} ${chunk.content}")
            tokens.toSet().forEach { term ->
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
            tokens
        }

        val totalDocs = allChunks.size.toDouble()
        val avgLen = tokenizedDocs.map { it.size }.average().coerceAtLeast(1.0)
        val k1 = 1.5
        val b = 0.75
        val lowerQuery = query.trim().lowercase()

        return allChunks.indices.map { idx ->
            val docTokens = tokenizedDocs[idx]
            val termCounts = docTokens.groupingBy { it }.eachCount()
            val docLen = docTokens.size.toDouble().coerceAtLeast(1.0)
            var score = 0.0

            queryTokens.distinct().forEach { qTerm ->
                val tf = (termCounts[qTerm] ?: 0).toDouble()
                if (tf > 0.0) {
                    val df = (docFreq[qTerm] ?: 0).toDouble()
                    val idf = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
                    val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgLen)))
                    score += idf * tfNorm
                }
            }

            if (lowerQuery.length >= 6 && allChunks[idx].content.lowercase().contains(lowerQuery)) {
                score += 2.5
            }

            allChunks[idx] to score
        }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    suspend fun renderPdfPagesToCache(
        context: Context,
        pdfUri: Uri,
        maxPages: Int = 8
    ): List<RenderedScanPage> = withContext(Dispatchers.IO) {
        val outDir = File(context.cacheDir, "camera_scans").apply {
            if (!exists()) mkdirs()
        }
        val rendered = mutableListOf<RenderedScanPage>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(pdfUri, "r") ?: return@withContext emptyList()
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount.coerceAtMost(maxPages)
            for (i in 0 until pageCount) {
                renderer.openPage(i).use { page ->
                    val targetWidth = (page.width * 2).coerceIn(900, 1440)
                    val targetHeight = (targetWidth.toFloat() / page.width.coerceAtLeast(1) * page.height)
                        .toInt()
                        .coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val outFile = File(outDir, "pdf_p${i + 1}_${UUID.randomUUID()}.jpg")
                    FileOutputStream(outFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                    }
                    bitmap.recycle()
                    rendered.add(
                        RenderedScanPage(
                            uriString = Uri.fromFile(outFile).toString(),
                            label = "Page ${i + 1}"
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Handled gracefully by caller
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
        rendered
    }

    suspend fun extractTextFromImageUris(
        context: Context,
        imageUris: List<Uri>
    ): String = withContext(Dispatchers.IO) {
        if (imageUris.isEmpty()) return@withContext ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val combined = StringBuilder()
        try {
            imageUris.forEachIndexed { idx, uri ->
                val text = runCatching {
                    val inputImage = InputImage.fromFilePath(context, uri)
                    suspendCancellableCoroutine { cont ->
                        recognizer.process(inputImage)
                            .addOnSuccessListener { result ->
                                if (cont.isActive) cont.resume(result.text.orEmpty())
                            }
                            .addOnFailureListener {
                                if (cont.isActive) cont.resume("")
                            }
                    }
                }.getOrDefault("")
                if (text.isNotBlank()) {
                    if (imageUris.size > 1) {
                        combined.append("--- Page ${idx + 1} ---\n")
                    }
                    combined.append(text.trim()).append("\n\n")
                }
            }
        } finally {
            runCatching { recognizer.close() }
        }
        combined.toString().trim()
    }

    suspend fun extractTextFromDocumentUri(
        context: Context,
        uri: Uri,
        mimeType: String?
    ): String = withContext(Dispatchers.IO) {
        val isPdf = mimeType?.contains("pdf", ignoreCase = true) == true ||
            uri.toString().lowercase().endsWith(".pdf")
        if (isPdf) {
            val pages = renderPdfPagesToCache(context, uri, maxPages = 10)
            if (pages.isEmpty()) return@withContext ""
            return@withContext extractTextFromImageUris(
                context = context,
                imageUris = pages.map { Uri.parse(it.uriString) }
            )
        }
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText().take(80_000)
            }
        }.getOrNull().orEmpty()
    }
}
