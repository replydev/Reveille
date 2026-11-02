package com.replydev.reveille.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FireLogDao {
    /** Returns -1 when the occurrence is already logged. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: FireLogEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM fire_log WHERE occurrenceKey = :key AND scheduledAt = :scheduledAt)")
    suspend fun exists(key: String, scheduledAt: Long): Boolean

    @Query("UPDATE fire_log SET outcome = :outcome WHERE occurrenceKey = :key AND scheduledAt = :scheduledAt")
    suspend fun setOutcome(key: String, scheduledAt: Long, outcome: FireOutcome)

    @Query("SELECT * FROM fire_log ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FireLogEntity>>

    /** Occurrences already fired, skipped or reported missed, scheduled at or after [since] (epoch millis). */
    @Query("SELECT occurrenceKey, scheduledAt FROM fire_log WHERE scheduledAt >= :since")
    suspend fun handledSince(since: Long): List<HandledOccurrence>
}
