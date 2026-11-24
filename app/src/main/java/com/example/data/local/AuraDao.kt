package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuraDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt ASC")
    suspend fun getAllConversationsOnce(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :id")
    suspend fun setConversationPinned(id: String, isPinned: Boolean)

    @Query("UPDATE conversations SET modelId = :modelId WHERE id = :id")
    suspend fun updateConversationModel(id: String, modelId: String)

    @Query("UPDATE conversations SET personaId = :personaId WHERE id = :id")
    suspend fun updateConversationPersona(id: String, personaId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesOnce(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("UPDATE messages SET isBookmarked = :bookmarked WHERE id = :messageId")
    suspend fun setBookmarked(messageId: String, bookmarked: Boolean)

    @Query("UPDATE messages SET isPinned = :pinned WHERE id = :messageId")
    suspend fun setMessagePinned(messageId: String, pinned: Boolean)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND timestamp >= :fromTimestamp")
    suspend fun deleteMessagesFromTimestamp(conversationId: String, fromTimestamp: Long)

    @Query("UPDATE messages SET content = :content, thinkingContent = :thinkingContent, thinkingDurationMs = :thinkingDurationMs, sourcesJson = :sourcesJson, variantsJson = :variantsJson, selectedVariantIndex = :selectedVariantIndex WHERE id = :messageId")
    suspend fun updateMessageVariantSelection(
        messageId: String,
        content: String,
        thinkingContent: String?,
        thinkingDurationMs: Long,
        sourcesJson: String?,
        variantsJson: String?,
        selectedVariantIndex: Int
    )

    @Query("SELECT * FROM messages WHERE isBookmarked = 1 ORDER BY timestamp DESC")
    fun observeBookmarkedMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM personas ORDER BY isCustom ASC, createdAt ASC")
    fun observePersonas(): Flow<List<PersonaEntity>>

    @Query("SELECT * FROM personas ORDER BY isCustom ASC, createdAt ASC")
    suspend fun getAllPersonasOnce(): List<PersonaEntity>

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun getPersonaCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersona(persona: PersonaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersonas(personas: List<PersonaEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPersonas(personas: List<PersonaEntity>)

    @Query("DELETE FROM personas WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustomPersona(id: String)

    // Personal Memory Vault
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE isEnabled = 1 ORDER BY updatedAt DESC")
    suspend fun getActiveMemoriesOnce(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getAllMemoriesOnce(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemories(memories: List<MemoryEntity>)

    @Query("UPDATE memories SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setMemoryEnabled(id: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()

    // Private Document Knowledge Vault (On-Device RAG)
    @Query("SELECT * FROM knowledge_docs ORDER BY createdAt DESC")
    fun observeKnowledgeDocs(): Flow<List<KnowledgeDocEntity>>

    @Query("SELECT * FROM knowledge_docs ORDER BY createdAt DESC")
    suspend fun getAllKnowledgeDocsOnce(): List<KnowledgeDocEntity>

    @Query("SELECT * FROM knowledge_chunks ORDER BY docId ASC, chunkIndex ASC")
    suspend fun getAllKnowledgeChunksOnce(): List<KnowledgeChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeDoc(doc: KnowledgeDocEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeChunks(chunks: List<KnowledgeChunkEntity>)

    @Query("DELETE FROM knowledge_docs WHERE id = :docId")
    suspend fun deleteKnowledgeDoc(docId: String)

    @Query("DELETE FROM knowledge_docs")
    suspend fun deleteAllKnowledgeDocs()

    // Deep Global Search across all chat messages & code blocks
    @Query(
        """
        SELECT m.id AS messageId,
               m.conversationId AS conversationId,
               c.title AS conversationTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM messages m
        INNER JOIN conversations c ON c.id = m.conversationId
        WHERE m.isError = 0
          AND m.content LIKE '%' || :query || '%'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessagesWithConversations(query: String, limit: Int = 25): List<MessageSearchHit>

    // Aura Autopilot Background Scheduled Agents
    @Query("SELECT * FROM autopilot_tasks ORDER BY createdAt DESC")
    fun observeAutopilotTasks(): Flow<List<AutopilotTaskEntity>>

    @Query("SELECT * FROM autopilot_tasks ORDER BY createdAt DESC")
    suspend fun getAllAutopilotTasksOnce(): List<AutopilotTaskEntity>

    @Query("SELECT * FROM autopilot_tasks WHERE isEnabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabledAutopilotTasksOnce(): List<AutopilotTaskEntity>

    @Query("SELECT * FROM autopilot_tasks WHERE id = :id LIMIT 1")
    suspend fun getAutopilotTaskById(id: String): AutopilotTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutopilotTask(task: AutopilotTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutopilotTasks(tasks: List<AutopilotTaskEntity>)

    @Query("UPDATE autopilot_tasks SET isEnabled = :enabled WHERE id = :id")
    suspend fun setAutopilotTaskEnabled(id: String, enabled: Boolean)

    @Query("UPDATE autopilot_tasks SET lastRunAt = :lastRunAt, lastResultSummary = :summary, lastConversationId = :convoId WHERE id = :id")
    suspend fun updateAutopilotTaskRun(id: String, lastRunAt: Long, summary: String, convoId: String)

    @Query("DELETE FROM autopilot_tasks WHERE id = :id")
    suspend fun deleteAutopilotTask(id: String)

    // Claude-Style Project Workspaces
    @Query("UPDATE conversations SET projectId = :projectId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationProject(id: String, projectId: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    suspend fun getAllProjectsOnce(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjects(projects: List<ProjectEntity>)

    @Query("UPDATE conversations SET projectId = NULL WHERE projectId = :projectId")
    suspend fun clearProjectFromConversations(projectId: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)

    // Versioned Artifact Hub
    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    fun observeArtifacts(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    suspend fun getAllArtifactsOnce(): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun getArtifactById(id: String): ArtifactEntity?

    @Query("SELECT MAX(versionNumber) FROM artifacts WHERE title = :title")
    suspend fun getArtifactMaxVersionForTitle(title: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifact(artifact: ArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifacts(artifacts: List<ArtifactEntity>)

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun deleteArtifact(id: String)

    // Reusable AI Studio Prompt Library
    @Query("SELECT * FROM prompt_templates ORDER BY isBuiltIn ASC, createdAt DESC")
    fun observePromptTemplates(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates ORDER BY isBuiltIn ASC, createdAt DESC")
    suspend fun getAllPromptTemplatesOnce(): List<PromptTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPromptTemplate(template: PromptTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPromptTemplates(templates: List<PromptTemplateEntity>)

    @Query("DELETE FROM prompt_templates WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustomPromptTemplate(id: String)

    // Custom Webhook / REST Tools (MCP-Lite)
    @Query("SELECT * FROM custom_webhooks ORDER BY createdAt DESC")
    fun observeCustomWebhooks(): Flow<List<CustomWebhookEntity>>

    @Query("SELECT * FROM custom_webhooks ORDER BY createdAt DESC")
    suspend fun getAllCustomWebhooksOnce(): List<CustomWebhookEntity>

    @Query("SELECT * FROM custom_webhooks WHERE isEnabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabledCustomWebhooksOnce(): List<CustomWebhookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomWebhook(webhook: CustomWebhookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomWebhooks(webhooks: List<CustomWebhookEntity>)

    @Query("UPDATE custom_webhooks SET isEnabled = :enabled WHERE id = :id")
    suspend fun setCustomWebhookEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM custom_webhooks WHERE id = :id")
    suspend fun deleteCustomWebhook(id: String)
}
