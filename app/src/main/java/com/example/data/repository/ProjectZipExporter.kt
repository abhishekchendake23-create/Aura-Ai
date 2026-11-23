package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class NamedCodeFile(
    val relativePath: String,
    val language: String,
    val content: String
)

object ProjectZipExporter {

    private val FENCED_BLOCK_REGEX = Regex("```([^\\n`]*)\\n([\\s\\S]*?)```")
    private val COMMENT_FILE_REGEX = Regex(
        "^(?://|#|--|/\\*|<!--)\\s*(?:File|Path)\\s*:\\s*([^\\s*]+)(?:\\s*\\*/|\\s*-->)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun extractNamedFilesFromMarkdown(markdown: String): List<NamedCodeFile> {
        if (markdown.isBlank()) return emptyList()
        val seenPaths = mutableSetOf<String>()
        val results = mutableListOf<NamedCodeFile>()

        for (match in FENCED_BLOCK_REGEX.findAll(markdown)) {
            val header = match.groupValues[1].trim()
            val body = match.groupValues[2].trimEnd()
            if (body.isBlank()) continue

            val colonIdx = header.indexOf(':')
            val lang = if (colonIdx > 0) header.substring(0, colonIdx).trim().lowercase() else header.lowercase()
            if (lang == "diff" || lang == "sandbox-output") continue

            val headerFile = if (colonIdx > 0) header.substring(colonIdx + 1).trim() else ""
            val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
            val commentFile = COMMENT_FILE_REGEX.find(firstLine)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val rawPath = headerFile.ifBlank { commentFile }
            if (rawPath.isBlank()) continue

            val safePath = sanitizeArchivePath(rawPath) ?: continue
            val dedupPath = deduplicatePath(safePath, seenPaths)
            seenPaths.add(dedupPath)

            val cleanedContent = if (commentFile.isNotBlank() && headerFile.isBlank()) {
                body.lineSequence().drop(1).joinToString("\n").trimStart('\r', '\n')
            } else {
                body
            }
            if (cleanedContent.isBlank()) continue

            results.add(
                NamedCodeFile(
                    relativePath = dedupPath,
                    language = lang.ifBlank { "txt" },
                    content = cleanedContent + "\n"
                )
            )
        }
        return results
    }

    fun sanitizeArchivePath(rawPath: String): String? {
        val normalized = rawPath
            .replace('\\', '/')
            .trim()
            .trimStart('/')
        if (normalized.isBlank()) return null
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." || it == "." || it.contains(':') }) return null
        return segments.joinToString("/")
    }

    private fun deduplicatePath(path: String, existing: Set<String>): String {
        if (path !in existing) return path
        val dot = path.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, dot) else path
        val ext = if (dot > 0) path.substring(dot) else ""
        var counter = 2
        while ("${base}_$counter$ext" in existing) {
            counter++
        }
        return "${base}_$counter$ext"
    }

    fun buildZipBytes(files: List<NamedCodeFile>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val written = mutableSetOf<String>()
            files.forEach { file ->
                val safe = sanitizeArchivePath(file.relativePath) ?: return@forEach
                val unique = deduplicatePath(safe, written)
                written.add(unique)
                zos.putNextEntry(ZipEntry(unique))
                zos.write(file.content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    fun exportMultiFileProjectZipUri(
        context: Context,
        markdown: String,
        projectName: String = "aura_project"
    ): Pair<Uri, Int> {
        val files = extractNamedFilesFromMarkdown(markdown)
        require(files.isNotEmpty()) { "No named files found to export." }
        val bytes = buildZipBytes(files)
        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists()) mkdirs()
        }
        val cleanBase = projectName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "aura_project" }
        val outFile = File(exportDir, "${cleanBase}_${System.currentTimeMillis()}.zip")
        FileOutputStream(outFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        return uri to files.size
    }
}
