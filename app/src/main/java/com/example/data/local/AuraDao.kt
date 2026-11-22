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
}
