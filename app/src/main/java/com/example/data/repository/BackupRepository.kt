package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.AuraDao
import com.example.data.local.ConversationEntity
import com.example.data.local.KnowledgeChunkEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupStats(
    val conversations: Int,
    val messages: Int,
    val memories: Int,
    val knowledgeDocs: Int,
    val customPersonas: Int
)

class BackupRepository(
    private val dao: AuraDao,
    private val context: Context
) {

    suspend fun exportToUri(uri: Uri): Result<BackupStats> = withContext(Dispatchers.IO) {
        runCatching {
            val (jsonString, stats) = buildBackupPayload()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(jsonString.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Could not open destination file for backup.")
            stats
        }
    }

    suspend fun importFromUri(uri: Uri): Result<BackupStats> = withContext(Dispatchers.IO) {
        runCatching {
            val rawJson = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: error("Could not read backup file.")
            restoreFromJsonString(rawJson)
        }
    }

    suspend fun buildBackupPayload(): Pair<String, BackupStats> {
        val conversations = dao.getAllConversationsOnce()
        val messages = dao.getAllMessagesOnce()
        val customPersonas = dao.getAllPersonasOnce().filter { it.isCustom }
        val memories = dao.getAllMemoriesOnce()
        val knowledgeDocs = dao.getAllKnowledgeDocsOnce()
        val knowledgeChunks = dao.getAllKnowledgeChunksOnce()

        val root = JSONObject().apply {
            put("app", "Aura AI")
            put("schemaVersion", 1)
            put("exportedAt", System.currentTimeMillis())

            put("conversations", JSONArray().apply {
                conversations.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("modelId", c.modelId)
                        put("personaId", c.personaId)
                        put("isPinned", c.isPinned)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                    })
                }
            })

            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("conversationId", m.conversationId)
                        put("role", m.role)
                        put("content", m.content)
                        m.thinkingContent?.let { put("thinkingContent", it) }
                        put("thinkingDurationMs", m.thinkingDurationMs)
                        put("modelDisplayName", m.modelDisplayName)
                        m.attachmentUri?.let { put("attachmentUri", it) }
                        m.attachmentName?.let { put("attachmentName", it) }
                        put("isImageAttachment", m.isImageAttachment)
                        m.generatedImagePath?.let { put("generatedImagePath", it) }
                        m.sourcesJson?.let { put("sourcesJson", it) }
                        put("isBookmarked", m.isBookmarked)
                        put("isError", m.isError)
                        put("timestamp", m.timestamp)
                    })
                }
            })

            put("customPersonas", JSONArray().apply {
                customPersonas.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("roleTag", p.roleTag)
                        put("description", p.description)
                        put("systemPrompt", p.systemPrompt)
                        put("iconKey", p.iconKey)
                        put("createdAt", p.createdAt)
                    })
                }
            })

            put("memories", JSONArray().apply {
                memories.forEach { mem ->
                    put(JSONObject().apply {
                        put("id", mem.id)
                        put("content", mem.content)
                        put("category", mem.category)
                        put("isEnabled", mem.isEnabled)
                        put("createdAt", mem.createdAt)
                        put("updatedAt", mem.updatedAt)
                    })
                }
            })

            put("knowledgeDocs", JSONArray().apply {
                knowledgeDocs.forEach { doc ->
                    put(JSONObject().apply {
                        put("id", doc.id)
                        put("title", doc.title)
                        put("sourceType", doc.sourceType)
                        put("chunkCount", doc.chunkCount)
                        put("createdAt", doc.createdAt)
                    })
                }
            })

            put("knowledgeChunks", JSONArray().apply {
                knowledgeChunks.forEach { chunk ->
                    put(JSONObject().apply {
                        put("id", chunk.id)
                        put("docId", chunk.docId)
                        put("docTitle", chunk.docTitle)
                        put("chunkIndex", chunk.chunkIndex)
                        put("content", chunk.content)
                    })
                }
            })
        }

        val stats = BackupStats(
            conversations = conversations.size,
            messages = messages.size,
            memories = memories.size,
            knowledgeDocs = knowledgeDocs.size,
            customPersonas = customPersonas.size
        )
        return root.toString(2) to stats
    }

    suspend fun restoreFromJsonString(rawJson: String): BackupStats {
        val root = JSONObject(rawJson)
        if (!root.has("conversations") && !root.has("memories") && !root.has("knowledgeDocs")) {
            error("Invalid Aura AI backup file format.")
        }

        val convosArray = root.optJSONArray("conversations") ?: JSONArray()
        val importedConversations = buildList {
            for (i in 0 until convosArray.length()) {
                val obj = convosArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    ConversationEntity(
                        id = id,
                        title = obj.optString("title", "Restored Chat"),
                        modelId = obj.optString("modelId", "gemini-3.8-flash"),
                        personaId = obj.optString("personaId", "persona_general"),
                        isPinned = obj.optBoolean("isPinned", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
        val convoIds = importedConversations.map { it.id }.toSet()
        importedConversations.forEach { dao.upsertConversation(it) }

        val msgsArray = root.optJSONArray("messages") ?: JSONArray()
        val importedMessages = buildList {
            for (i in 0 until msgsArray.length()) {
                val obj = msgsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val convoId = obj.optString("conversationId").takeIf { it in convoIds } ?: continue
                add(
                    MessageEntity(
                        id = id,
                        conversationId = convoId,
                        role = obj.optString("role", "user"),
                        content = obj.optString("content", ""),
                        thinkingContent = obj.optString("thinkingContent").takeIf { it.isNotBlank() && obj.has("thinkingContent") },
                        thinkingDurationMs = obj.optLong("thinkingDurationMs", 0L),
                        modelDisplayName = obj.optString("modelDisplayName", "Aura AI"),
                        attachmentUri = obj.optString("attachmentUri").takeIf { it.isNotBlank() && obj.has("attachmentUri") },
                        attachmentName = obj.optString("attachmentName").takeIf { it.isNotBlank() && obj.has("attachmentName") },
                        isImageAttachment = obj.optBoolean("isImageAttachment", false),
                        generatedImagePath = obj.optString("generatedImagePath").takeIf { it.isNotBlank() && obj.has("generatedImagePath") },
                        sourcesJson = obj.optString("sourcesJson").takeIf { it.isNotBlank() && obj.has("sourcesJson") },
                        isBookmarked = obj.optBoolean("isBookmarked", false),
                        isError = obj.optBoolean("isError", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedMessages.isNotEmpty()) {
            dao.upsertMessages(importedMessages)
        }

        val personasArray = root.optJSONArray("customPersonas") ?: JSONArray()
        val importedPersonas = buildList {
            for (i in 0 until personasArray.length()) {
                val obj = personasArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    PersonaEntity(
                        id = id,
                        name = obj.optString("name", "Custom Persona"),
                        roleTag = obj.optString("roleTag", "Custom"),
                        description = obj.optString("description", ""),
                        systemPrompt = obj.optString("systemPrompt", ""),
                        iconKey = obj.optString("iconKey", "sparkle"),
                        isCustom = true,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedPersonas.isNotEmpty()) {
            dao.upsertPersonas(importedPersonas)
        }

        val memArray = root.optJSONArray("memories") ?: JSONArray()
        val importedMemories = buildList {
            for (i in 0 until memArray.length()) {
                val obj = memArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val content = obj.optString("content").takeIf { it.isNotBlank() } ?: continue
                add(
                    MemoryEntity(
                        id = id,
                        content = content,
                        category = obj.optString("category", "General"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedMemories.isNotEmpty()) {
            dao.upsertMemories(importedMemories)
        }

        val docsArray = root.optJSONArray("knowledgeDocs") ?: JSONArray()
        val importedDocs = buildList {
            for (i in 0 until docsArray.length()) {
                val obj = docsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    KnowledgeDocEntity(
                        id = id,
                        title = obj.optString("title", "Document"),
                        sourceType = obj.optString("sourceType", "Text"),
                        chunkCount = obj.optInt("chunkCount", 0),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        val docIds = importedDocs.map { it.id }.toSet()
        importedDocs.forEach { dao.upsertKnowledgeDoc(it) }

        val chunksArray = root.optJSONArray("knowledgeChunks") ?: JSONArray()
        val importedChunks = buildList {
            for (i in 0 until chunksArray.length()) {
                val obj = chunksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val docId = obj.optString("docId").takeIf { it in docIds } ?: continue
                add(
                    KnowledgeChunkEntity(
                        id = id,
                        docId = docId,
                        docTitle = obj.optString("docTitle", "Document"),
                        chunkIndex = obj.optInt("chunkIndex", 0),
                        content = obj.optString("content", "")
                    )
                )
            }
        }
        if (importedChunks.isNotEmpty()) {
            dao.upsertKnowledgeChunks(importedChunks)
        }

        return BackupStats(
            conversations = importedConversations.size,
            messages = importedMessages.size,
            memories = importedMemories.size,
            knowledgeDocs = importedDocs.size,
            customPersonas = importedPersonas.size
        )
    }
}
