package com.example.data.repository

import com.example.data.local.AuraDao
import com.example.data.local.ConversationEntity
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

    suspend fun ensureDefaultPersonas() {
        if (dao.getPersonaCount() == 0) {
            dao.insertPersonas(defaultPersonas())
        }
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
        dao.upsertConversation(conversation)
        return conversation
    }

    suspend fun getConversation(id: String): ConversationEntity? = dao.getConversationById(id)

    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity> =
        dao.getMessagesOnce(conversationId)

    suspend fun saveMessage(message: MessageEntity) {
        dao.upsertMessage(message)
        val convo = dao.getConversationById(message.conversationId)
        if (convo != null) {
            dao.upsertConversation(convo.copy(updatedAt = System.currentTimeMillis()))
        }
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

    private fun defaultPersonas(): List<PersonaEntity> = listOf(
        PersonaEntity(
            id = "persona_general",
            name = "Aura Core",
            roleTag = "All-Purpose",
            description = "Balanced intelligence for synthesis, writing, brainstorming, and daily workflows.",
            systemPrompt = "You are Aura, a warm, precise, and highly capable AI partner.",
            iconKey = "sparkle"
        ),
        PersonaEntity(
            id = "persona_engineer",
            name = "Principal Engineer",
            roleTag = "Kotlin & Systems",
            description = "Production-ready Android, Jetpack Compose, backend architecture, and debugging.",
            systemPrompt = "Provide clean, idiomatic Kotlin and systems architecture with edge-case awareness.",
            iconKey = "code"
        ),
        PersonaEntity(
            id = "persona_analyst",
            name = "Strategy Analyst",
            roleTag = "Deep Research",
            description = "Structured trade-offs, frameworks, risk matrices, and executive decision briefs.",
            systemPrompt = "Break down complex decisions into structured options, trade-offs, and clear recommendations.",
            iconKey = "analytics"
        ),
        PersonaEntity(
            id = "persona_writer",
            name = "Editorial Writer",
            roleTag = "Voice & Craft",
            description = "Sharp copywriting, storytelling, product messaging, and multilingual tone polish.",
            systemPrompt = "Write vivid, concise prose tailored to audience and rhythm without filler.",
            iconKey = "edit"
        ),
        PersonaEntity(
            id = "persona_tutor",
            name = "Socratic Coach",
            roleTag = "Learn & Master",
            description = "Intuitive mental models, step-by-step scaffolding, and active recall questions.",
            systemPrompt = "Teach from first principles using clear analogies and short check-in questions.",
            iconKey = "school"
        )
    )
}
