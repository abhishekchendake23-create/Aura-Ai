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

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("UPDATE messages SET isBookmarked = :bookmarked WHERE id = :messageId")
    suspend fun setBookmarked(messageId: String, bookmarked: Boolean)

    @Query("SELECT * FROM messages WHERE isBookmarked = 1 ORDER BY timestamp DESC")
    fun observeBookmarkedMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM personas ORDER BY isCustom ASC, createdAt ASC")
    fun observePersonas(): Flow<List<PersonaEntity>>

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
}
