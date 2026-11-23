package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ProjectImportResult(
    val displayName: String,
    val formattedContext: String,
    val fileCount: Int,
    val isZipArchive: Boolean
)

object ProjectZipReader {

    private const val MAX_TOTAL_CONTEXT_CHARS = 18_000
    private const val MAX_SINGLE_FILE_CHARS = 5_500
    private const val MAX_FILES_INCLUDED = 24
    private const val MAX_ZIP_ENTRY_BYTES = 512_000L

    private val SUPPORTED_EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "ts", "tsx", "js", "jsx", "mjs", "cjs",
        "html", "htm", "css", "scss", "svg", "xml", "json", "yaml", "yml",
        "toml", "gradle", "properties", "md", "txt", "csv", "tsv", "sql", "sh",
        "c", "h", "cpp", "hpp", "rs", "go", "dart", "swift", "rb", "php",
        "env", "gitignore", "dockerfile", "diff", "patch"
    )

    private val IGNORED_DIR_SEGMENTS = setOf(
        ".git", ".gradle", "build", "node_modules", ".idea", "__pycache__",
        ".venv", "venv", "dist", "out", ".next", "target", ".dart_tool"
    )

    fun isZipFileName(name: String): Boolean = name.trim().lowercase().endsWith(".zip")

    fun isSupportedTextOrCodeFile(path: String): Boolean {
        val clean = path.trim().lowercase()
        val fileName = clean.substringAfterLast('/')
        if (fileName.startsWith(".") && fileName !in setOf(".env.example", ".gitignore")) {
            return false
        }
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in SUPPORTED_EXTENSIONS) return true
        return fileName in setOf("dockerfile", "makefile", "readme", "license", "procfile")
    }

    fun sanitizeZipEntryPath(rawName: String): String? {
        val normalized = rawName.replace('\\', '/').trim().trimStart('/')
        if (normalized.isBlank() || normalized.contains("..")) return null
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.any { it.lowercase() in IGNORED_DIR_SEGMENTS }) return null
        return segments.joinToString("/")
    }

    fun readFromUris(context: Context, uris: List<Uri>): ProjectImportResult? {
        if (uris.isEmpty()) return null
        val resolver = context.contentResolver
        val collectedFiles = mutableListOf<Pair<String, String>>()
        var foundZip = false
        var primaryName = "Project Workspace"

        for (uri in uris) {
            val fileName = resolveDisplayName(context, uri)
            if (uris.size == 1) primaryName = fileName
            val mime = resolver.getType(uri).orEmpty().lowercase()

            if (isZipFileName(fileName) || mime == "application/zip" || mime == "application/x-zip-compressed") {
                foundZip = true
                resolver.openInputStream(uri)?.use { stream ->
                    collectedFiles += extractReadableFilesFromZipStream(stream)
                }
            } else {
                resolver.openInputStream(uri)?.use { stream ->
                    val content = readBoundedUtf8Text(stream, MAX_SINGLE_FILE_CHARS)
                    if (content.isNotBlank()) {
                        collectedFiles += fileName to content
                    }
                }
            }
        }

        if (collectedFiles.isEmpty()) return null

        val deduped = collectedFiles.distinctBy { it.first }.take(MAX_FILES_INCLUDED)
        val formatted = formatProjectContext(deduped)
        val badgeName = when {
            foundZip && uris.size == 1 -> "$primaryName (${deduped.size} files)"
            deduped.size == 1 -> deduped.first().first
            else -> "Project Workspace (${deduped.size} files)"
        }
        return ProjectImportResult(
            displayName = badgeName,
            formattedContext = formatted,
            fileCount = deduped.size,
            isZipArchive = foundZip
        )
    }

    fun extractReadableFilesFromZipBytes(zipBytes: ByteArray): List<Pair<String, String>> {
        return ByteArrayInputStream(zipBytes).use { extractReadableFilesFromZipStream(it) }
    }

    fun extractReadableFilesFromZipStream(inputStream: InputStream): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            var totalBytesVisited = 0L
            while (entry != null && result.size < MAX_FILES_INCLUDED && totalBytesVisited < 4_000_000L) {
                if (!entry.isDirectory) {
                    val safePath = sanitizeZipEntryPath(entry.name)
                    if (safePath != null && isSupportedTextOrCodeFile(safePath)) {
                        val buffer = ByteArray(4096)
                        val out = java.io.ByteArrayOutputStream()
                        var entryRead = 0L
                        while (entryRead < MAX_ZIP_ENTRY_BYTES) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            entryRead += read
                            totalBytesVisited += read
                        }
                        val text = out.toString(Charsets.UTF_8.name()).trim()
                        if (text.isNotBlank() && !looksBinary(text)) {
                            val clipped = if (text.length > MAX_SINGLE_FILE_CHARS) {
                                text.take(MAX_SINGLE_FILE_CHARS) + "\n// ... [truncated for context budget]"
                            } else {
                                text
                            }
                            result += safePath to clipped
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    fun formatProjectContext(files: List<Pair<String, String>>): String {
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("### Project Files Index (${files.size} files)\n")
        files.forEachIndexed { index, (path, _) ->
            sb.append("${index + 1}. $path\n")
        }
        sb.append("\n")

        for ((path, content) in files) {
            val csvSummary = summarizeCsvIfApplicable(path, content)
            val block = buildString {
                if (csvSummary != null) {
                    append(csvSummary).append("\n")
                }
                append("// File: ").append(path).append("\n").append(content).append("\n\n")
            }
            if (sb.length + block.length > MAX_TOTAL_CONTEXT_CHARS) {
                val remaining = MAX_TOTAL_CONTEXT_CHARS - sb.length
                if (remaining > 180) {
                    sb.append(block.take(remaining)).append("\n// ... [workspace context limit reached]")
                }
                break
            }
            sb.append(block)
        }
        return sb.toString().trim()
    }

    fun summarizeCsvIfApplicable(fileName: String, content: String): String? {
        val lower = fileName.trim().lowercase()
        val isTsv = lower.endsWith(".tsv")
        if (!lower.endsWith(".csv") && !isTsv) return null
        val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val delimiter = if (isTsv) '\t' else ','
        val headers = splitCsvRow(lines.first(), delimiter)
        val dataRows = lines.drop(1).map { splitCsvRow(it, delimiter) }
        val numericStats = buildList {
            headers.forEachIndexed { colIdx, colName ->
                val nums = dataRows.mapNotNull { row ->
                    row.getOrNull(colIdx)?.replace(",", "")?.toDoubleOrNull()
                }
                if (nums.size >= 2) {
                    val min = nums.min()
                    val max = nums.max()
                    val avg = nums.average()
                    add("$colName (n=${nums.size}, min=%.2f, max=%.2f, avg=%.2f)".format(java.util.Locale.US, min, max, avg))
                }
            }
        }
        return buildString {
            append("[CSV Analytics Pre-Summary for ").append(fileName).append("]\n")
            append("- Columns (${headers.size}): ${headers.joinToString(", ")}\n")
            append("- Total Data Rows Parsed: ${dataRows.size}\n")
            if (numericStats.isNotEmpty()) {
                append("- Numeric Columns: ${numericStats.joinToString(" | ")}\n")
            }
        }.trim()
    }

    private fun splitCsvRow(line: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    out += cur.toString().trim()
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        out += cur.toString().trim()
        return out
    }

    private fun readBoundedUtf8Text(stream: InputStream, maxChars: Int): String {
        val text = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buf = CharArray(maxChars + 1)
            val count = reader.read(buf, 0, maxChars + 1)
            if (count <= 0) "" else String(buf, 0, count)
        }
        return if (text.length > maxChars) {
            text.take(maxChars) + "\n// ... [file clipped]"
        } else {
            text
        }
    }

    private fun looksBinary(text: String): Boolean {
        val sample = text.take(400)
        val controlCount = sample.count { ch -> ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' }
        return controlCount > sample.length / 10
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) {
                                val name = cursor.getString(idx)
                                if (!name.isNullOrBlank()) return name
                            }
                        }
                    }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "file.txt" } ?: "file.txt"
    }
}
