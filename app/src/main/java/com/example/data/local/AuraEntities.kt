package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String,
    val personaId: String,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: String? = null
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["conversationId"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val thinkingContent: String? = null,
    val thinkingDurationMs: Long = 0L,
    val modelDisplayName: String = "",
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    val isImageAttachment: Boolean = false,
    val generatedImagePath: String? = null,
    val sourcesJson: String? = null,
    val isBookmarked: Boolean = false,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val variantsJson: String? = null,
    val selectedVariantIndex: Int = 0
)

data class MessageVariant(
    val content: String,
    val thinkingContent: String? = null,
    val thinkingDurationMs: Long = 0L,
    val sourcesJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val engineLabel: String? = null
)

fun serializeMessageVariants(variants: List<MessageVariant>): String? {
    if (variants.isEmpty()) return null
    val array = org.json.JSONArray()
    variants.forEach { v ->
        val obj = org.json.JSONObject()
        obj.put("content", v.content)
        v.thinkingContent?.let { obj.put("thinkingContent", it) }
        obj.put("thinkingDurationMs", v.thinkingDurationMs)
        v.sourcesJson?.let { obj.put("sourcesJson", it) }
        obj.put("createdAt", v.createdAt)
        v.engineLabel?.let { obj.put("engineLabel", it) }
        array.put(obj)
    }
    return array.toString()
}

fun parseMessageVariants(rawJson: String?): List<MessageVariant> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(rawJson)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val content = obj.optString("content")
                if (content.isNotBlank()) {
                    add(
                        MessageVariant(
                            content = content,
                            thinkingContent = obj.optString("thinkingContent")
                                .takeIf { it.isNotBlank() && obj.has("thinkingContent") && !obj.isNull("thinkingContent") },
                            thinkingDurationMs = obj.optLong("thinkingDurationMs", 0L),
                            sourcesJson = obj.optString("sourcesJson")
                                .takeIf { it.isNotBlank() && obj.has("sourcesJson") && !obj.isNull("sourcesJson") },
                            createdAt = obj.optLong("createdAt", 0L),
                            engineLabel = obj.optString("engineLabel")
                                .takeIf { it.isNotBlank() && obj.has("engineLabel") && !obj.isNull("engineLabel") }
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())
}

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val roleTag: String,
    val description: String,
    val systemPrompt: String,
    val iconKey: String,
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String = "General",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "knowledge_docs")
data class KnowledgeDocEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceType: String, // "PDF", "Scan", "Text", "Note"
    val chunkCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["docId"])]
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    val docId: String,
    val docTitle: String,
    val chunkIndex: Int,
    val content: String
)

@Entity(tableName = "autopilot_tasks")
data class AutopilotTaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val prompt: String,
    val intervalHours: Int = 24,
    val useWebSearch: Boolean = true,
    val isEnabled: Boolean = true,
    val lastRunAt: Long = 0L,
    val lastResultSummary: String = "",
    val lastConversationId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val customInstructions: String = "",
    val pinnedUrlsOrNotes: String = "",
    val accentHex: String = "#7AA2FF",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "artifacts",
    indices = [Index(value = ["conversationId"]), Index(value = ["messageId"])]
)
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String,
    val title: String,
    val language: String,
    val code: String,
    val versionNumber: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String = "General",
    val systemPrompt: String = "",
    val userTemplate: String,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_webhooks")
data class CustomWebhookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val endpointUrl: String,
    val httpMethod: String = "GET",
    val headersJson: String = "",
    val bodyTemplate: String = "",
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class MessageSearchHit(
    val messageId: String,
    val conversationId: String,
    val conversationTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)
