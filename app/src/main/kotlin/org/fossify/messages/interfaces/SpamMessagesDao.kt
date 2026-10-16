package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.fossify.messages.models.SpamMessage

@Dao
interface SpamMessagesDao {
    @Query("SELECT * FROM spam_messages ORDER BY date DESC")
    fun getAll(): List<SpamMessage>

    @Insert
    fun insert(message: SpamMessage): Long

    @Query("DELETE FROM spam_messages WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM spam_messages")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM spam_messages WHERE date < :date")
    fun countOlderThan(date: Long): Int

    @Query("DELETE FROM spam_messages WHERE date < :date")
    fun deleteOlderThan(date: Long): Int
}
