package com.example.messenger_app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC")
    fun getMessages(chatId: String): Flow<List<LocalMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<LocalMessage>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessages(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id NOT IN (:currentIds)")
    suspend fun deleteMessagesNotIn(chatId: String, currentIds: List<String>)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY timestamp DESC")
    fun getChats(): Flow<List<LocalChat>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<LocalChat>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)
    
    @Query("DELETE FROM chats")
    suspend fun clearChats()

    @Query("DELETE FROM chats WHERE id NOT IN (:currentIds)")
    suspend fun deleteChatsNotIn(currentIds: List<String>)
}
