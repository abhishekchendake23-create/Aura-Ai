package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.ArtifactEntity
import com.example.data.local.AuraDao
import com.example.data.local.AutopilotTaskEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.CustomWebhookEntity
import com.example.data.local.KnowledgeChunkEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import com.example.data.local.ProjectEntity
import com.example.data.local.PromptTemplateEntity
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
        val autopilotTasks = dao.getAllAutopilotTasksOnce()
        val projects = dao.getAllProjectsOnce()
        val artifacts = dao.getAllArtifactsOnce()
        val promptTemplates = dao.getAllPromptTemplatesOnce().filter { !it.isBuiltIn }
        val customWebhooks = dao.getAllCustomWebhooksOnce()

        val root = JSONObject().apply {
            put("app", "Aura AI")
            put("schemaVersion", 2)
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
                        c.projectId?.let { put("projectId", it) }
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
                        put("isPinned", m.isPinned)
                        m.variantsJson?.let { put("variantsJson", it) }
                        put("selectedVariantIndex", m.selectedVariantIndex)
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

            put("autopilotTasks", JSONArray().apply {
                autopilotTasks.forEach { task ->
                    put(JSONObject().apply {
                        put("id", task.id)
                        put("title", task.title)
                        put("prompt", task.prompt)
                        put("intervalHours", task.intervalHours)
                        put("useWebSearch", task.useWebSearch)
                        put("isEnabled", task.isEnabled)
                        put("lastRunAt", task.lastRunAt)
                        put("lastResultSummary", task.lastResultSummary)
                        task.lastConversationId?.let { put("lastConversationId", it) }
                        put("createdAt", task.createdAt)
                    })
                }
            })

            put("projects", JSONArray().apply {
                projects.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("description", p.description)
                        put("customInstructions", p.customInstructions)
                        put("pinnedUrlsOrNotes", p.pinnedUrlsOrNotes)
                        put("accentHex", p.accentHex)
                        put("createdAt", p.createdAt)
                        put("updatedAt", p.updatedAt)
                    })
                }
            })

            put("artifacts", JSONArray().apply {
                artifacts.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id)
                        put("conversationId", a.conversationId)
                        put("messageId", a.messageId)
                        put("title", a.title)
                        put("language", a.language)
                        put("code", a.code)
                        put("versionNumber", a.versionNumber)
                        put("createdAt", a.createdAt)
                    })
                }
            })

            put("promptTemplates", JSONArray().apply {
                promptTemplates.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("category", t.category)
                        put("systemPrompt", t.systemPrompt)
                        put("userTemplate", t.userTemplate)
                        put("createdAt", t.createdAt)
                    })
                }
            })

            put("customWebhooks", JSONArray().apply {
                customWebhooks.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("name", w.name)
                        put("description", w.description)
                        put("endpointUrl", w.endpointUrl)
                        put("httpMethod", w.httpMethod)
                        put("headersJson", w.headersJson)
                        put("bodyTemplate", w.bodyTemplate)
                        put("isEnabled", w.isEnabled)
                        put("createdAt", w.createdAt)
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
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        projectId = obj.optString("projectId").takeIf { it.isNotBlank() && obj.has("projectId") }
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
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", false),
                        variantsJson = obj.optString("variantsJson").takeIf { it.isNotBlank() && obj.has("variantsJson") },
                        selectedVariantIndex = obj.optInt("selectedVariantIndex", 0)
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

        val tasksArray = root.optJSONArray("autopilotTasks") ?: JSONArray()
        val importedTasks = buildList {
            for (i in 0 until tasksArray.length()) {
                val obj = tasksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue
                val prompt = obj.optString("prompt").takeIf { it.isNotBlank() } ?: continue
                add(
                    AutopilotTaskEntity(
                        id = id,
                        title = title,
                        prompt = prompt,
                        intervalHours = obj.optInt("intervalHours", 24),
                        useWebSearch = obj.optBoolean("useWebSearch", true),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        lastRunAt = obj.optLong("lastRunAt", 0L),
                        lastResultSummary = obj.optString("lastResultSummary", ""),
                        lastConversationId = obj.optString("lastConversationId").takeIf { it.isNotBlank() && obj.has("lastConversationId") },
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedTasks.isNotEmpty()) {
            dao.upsertAutopilotTasks(importedTasks)
        }

        val projectsArray = root.optJSONArray("projects") ?: JSONArray()
        val importedProjects = buildList {
            for (i in 0 until projectsArray.length()) {
                val obj = projectsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    ProjectEntity(
                        id = id,
                        name = obj.optString("name", "Workspace"),
                        description = obj.optString("description", ""),
                        customInstructions = obj.optString("customInstructions", ""),
                        pinnedUrlsOrNotes = obj.optString("pinnedUrlsOrNotes", ""),
                        accentHex = obj.optString("accentHex", "#7AA2FF"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedProjects.isNotEmpty()) dao.upsertProjects(importedProjects)

        val artifactsArray = root.optJSONArray("artifacts") ?: JSONArray()
        val importedArtifacts = buildList {
            for (i in 0 until artifactsArray.length()) {
                val obj = artifactsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    ArtifactEntity(
                        id = id,
                        conversationId = obj.optString("conversationId", ""),
                        messageId = obj.optString("messageId", ""),
                        title = obj.optString("title", "Artifact"),
                        language = obj.optString("language", "html"),
                        code = obj.optString("code", ""),
                        versionNumber = obj.optInt("versionNumber", 1),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedArtifacts.isNotEmpty()) dao.upsertArtifacts(importedArtifacts)

        val templatesArray = root.optJSONArray("promptTemplates") ?: JSONArray()
        val importedTemplates = buildList {
            for (i in 0 until templatesArray.length()) {
                val obj = templatesArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val userTemplate = obj.optString("userTemplate").takeIf { it.isNotBlank() } ?: continue
                add(
                    PromptTemplateEntity(
                        id = id,
                        title = obj.optString("title", "Custom Prompt"),
                        category = obj.optString("category", "Custom"),
                        systemPrompt = obj.optString("systemPrompt", ""),
                        userTemplate = userTemplate,
                        isBuiltIn = false,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedTemplates.isNotEmpty()) dao.upsertPromptTemplates(importedTemplates)

        val webhooksArray = root.optJSONArray("customWebhooks") ?: JSONArray()
        val importedWebhooks = buildList {
            for (i in 0 until webhooksArray.length()) {
                val obj = webhooksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val url = obj.optString("endpointUrl").takeIf { it.isNotBlank() } ?: continue
                add(
                    CustomWebhookEntity(
                        id = id,
                        name = obj.optString("name", "Custom Tool"),
                        description = obj.optString("description", ""),
                        endpointUrl = url,
                        httpMethod = obj.optString("httpMethod", "GET"),
                        headersJson = obj.optString("headersJson", ""),
                        bodyTemplate = obj.optString("bodyTemplate", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        }
        if (importedWebhooks.isNotEmpty()) dao.upsertCustomWebhooks(importedWebhooks)

        return BackupStats(
            conversations = importedConversations.size,
            messages = importedMessages.size,
            memories = importedMemories.size,
            knowledgeDocs = importedDocs.size,
            customPersonas = importedPersonas.size
        )
    }
}
