package com.example.data.repository

import com.example.data.local.AuraDao
import com.example.data.local.ConversationEntity
import com.example.data.local.KnowledgeChunkEntity
import com.example.data.local.KnowledgeDocEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.MessageEntity
import com.example.data.local.PersonaEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(private val dao: AuraDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    fun observeBookmarkedMessages(): Flow<List<MessageEntity>> = dao.observeBookmarkedMessages()

    fun observePersonas(): Flow<List<PersonaEntity>> = dao.observePersonas()

    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeMemories()

    fun observeKnowledgeDocs(): Flow<List<KnowledgeDocEntity>> = dao.observeKnowledgeDocs()

    suspend fun ensureDefaultPersonas() {
        dao.upsertPersonas(defaultPersonas())
    }

    suspend fun createConversation(
        title: String,
        modelId: String,
        personaId: String
    ): ConversationEntity {
        val now = System.currentTimeMillis()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title.take(64).ifBlank { "New Conversation" },
            modelId = modelId,
            personaId = personaId,
            isPinned = false,
            createdAt = now,
            updatedAt = now
        )
        dao.insertConversation(conversation)
        return conversation
    }

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversationById(id)

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        dao.getMessagesOnce(conversationId)

    suspend fun saveMessage(message: MessageEntity) {
        dao.upsertMessage(message)
        dao.touchConversation(message.conversationId, System.currentTimeMillis())
    }

    suspend fun deleteMessage(messageId: String) = dao.deleteMessage(messageId)

    suspend fun toggleBookmark(messageId: String, currentValue: Boolean) =
        dao.setBookmarked(messageId, !currentValue)

    suspend fun renameConversation(id: String, newTitle: String) {
        if (newTitle.isNotBlank()) {
            dao.renameConversation(id, newTitle.trim())
        }
    }

    suspend fun togglePinConversation(id: String, currentPinned: Boolean) =
        dao.setConversationPinned(id, !currentPinned)

    suspend fun updateConversationModel(id: String, modelId: String) =
        dao.updateConversationModel(id, modelId)

    suspend fun updateConversationPersona(id: String, personaId: String) =
        dao.updateConversationPersona(id, personaId)

    suspend fun deleteConversation(id: String) = dao.deleteConversation(id)

    suspend fun clearAllHistory() = dao.deleteAllConversations()

    suspend fun createCustomPersona(
        name: String,
        roleTag: String,
        description: String,
        systemPrompt: String,
        iconKey: String
    ): PersonaEntity {
        val persona = PersonaEntity(
            id = "custom_${UUID.randomUUID()}",
            name = name.trim(),
            roleTag = roleTag.trim().ifBlank { "Custom" },
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            iconKey = iconKey,
            isCustom = true
        )
        dao.upsertPersona(persona)
        return persona
    }

    suspend fun deleteCustomPersona(id: String) = dao.deleteCustomPersona(id)

    // Personal Memory Vault ("Aura Remembers")
    suspend fun getActiveMemoriesOnce(): List<MemoryEntity> = dao.getActiveMemoriesOnce()

    suspend fun addMemory(content: String, category: String = "General"): MemoryEntity {
        val now = System.currentTimeMillis()
        val memory = MemoryEntity(
            id = UUID.randomUUID().toString(),
            content = content.trim(),
            category = category.trim().ifBlank { "General" },
            isEnabled = true,
            createdAt = now,
            updatedAt = now
        )
        dao.upsertMemory(memory)
        return memory
    }

    suspend fun toggleMemory(id: String, currentEnabled: Boolean) =
        dao.setMemoryEnabled(id, !currentEnabled)

    suspend fun deleteMemory(id: String) = dao.deleteMemory(id)

    // Private Document Knowledge Vault (On-Device RAG)
    suspend fun getAllKnowledgeChunksOnce(): List<KnowledgeChunkEntity> =
        dao.getAllKnowledgeChunksOnce()

    suspend fun saveKnowledgeDocument(
        doc: KnowledgeDocEntity,
        chunks: List<KnowledgeChunkEntity>
    ) {
        dao.upsertKnowledgeDoc(doc)
        if (chunks.isNotEmpty()) {
            dao.upsertKnowledgeChunks(chunks)
        }
    }

    suspend fun deleteKnowledgeDoc(docId: String) = dao.deleteKnowledgeDoc(docId)

    private fun defaultPersonas(): List<PersonaEntity> = listOf(
        PersonaEntity(
            id = "persona_general",
            name = "Aura Core",
            roleTag = "All-Purpose",
            description = "Frontier clarity for coding, KaTeX math, reasoning & everyday mastery.",
            systemPrompt = "You are Aura AI, created by Abhi at Abhynex — deliver ChatGPT/Claude-level structured clarity, action-first answers, KaTeX equations, and zero-error code.",
            iconKey = "sparkle",
            createdAt = 1L
        ),
        PersonaEntity(
            id = "persona_engineer",
            name = "Code Architect",
            roleTag = "Staff Engineer",
            description = "Claude-tier zero-error coding: complete imports, concurrency safety, MVVM & system design.",
            systemPrompt = "Act as a Staff Software Engineer & Systems Architect. Analyze constraints before coding, write complete compile-ready code with zero placeholders or ellipsis, enforce null/concurrency/resource safety, handle all edge cases, and include verification steps or unit tests.",
            iconKey = "code",
            createdAt = 2L
        ),
        PersonaEntity(
            id = "persona_reviewer",
            name = "Bug Hunter",
            roleTag = "Zero-Error Audit",
            description = "Principal code reviewer hunting race conditions, crashes, leaks & security flaws.",
            systemPrompt = "Act as a Principal Security & Reliability Reviewer. Critically audit code for null-pointer crashes, race conditions, memory/coroutine leaks, SQL/auth vulnerabilities, off-by-one errors, and silent failures. List findings by severity (Critical/High/Medium), explain root causes, and provide the complete drop-in fixed code.",
            iconKey = "bug",
            createdAt = 3L
        ),
        PersonaEntity(
            id = "persona_math",
            name = "Math & STEM Pro",
            roleTag = "KaTeX Olympiad",
            description = "Step-by-step JEE, Olympiad, Calculus & Physics derivations with boxed verification.",
            systemPrompt = "Act as an Olympiad & JEE Advanced Mathematics/Physics Professor. Never wait for sample problems when asked for hard questions — select a genuinely challenging problem and solve every step clearly with KaTeX display blocks ($$...$$), sanity-check the result, and place the final answer in $$\\boxed{...}$$.",
            iconKey = "math",
            createdAt = 4L
        ),
        PersonaEntity(
            id = "persona_analyst",
            name = "Deep Researcher",
            roleTag = "Strategy & Web",
            description = "First-principles research, Markdown trade-off matrices & verified executive briefs.",
            systemPrompt = "Act as a Principal Strategy & Technical Researcher. Synthesize complex topics from first principles using structured Markdown comparison tables, quantifiable trade-offs, risk matrices, and concise actionable recommendations.",
            iconKey = "search",
            createdAt = 5L
        ),
        PersonaEntity(
            id = "persona_writer",
            name = "Marathi & Copy Pro",
            roleTag = "Voice & Craft",
            description = "Sharp, natural Marathi, Romanized Marathi, Hindi & English communication.",
            systemPrompt = "Act as a master multilingual editor and communicator fluent in Marathi, Romanized Marathi, Hindi, and English. Match the user's exact language and register effortlessly with crisp, vivid, zero-fluff prose.",
            iconKey = "edit",
            createdAt = 6L
        )
    )
}
