package com.feedback.sdk.db.dao

import androidx.room.*
import com.feedback.sdk.db.entity.PendingFeedback
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedbackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: PendingFeedback)

    @Update
    suspend fun update(feedback: PendingFeedback)

    @Query("SELECT * FROM pending_feedback WHERE clientFeedbackId = :id")
    suspend fun getById(id: String): PendingFeedback?

    @Query("SELECT * FROM pending_feedback WHERE status IN ('PENDING_BODY', 'PENDING_ATTACHMENTS') ORDER BY createdAt ASC")
    suspend fun getPending(): List<PendingFeedback>

    @Query("SELECT * FROM pending_feedback WHERE status = 'PENDING_BODY' ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingBodies(limit: Int = 20): List<PendingFeedback>

    @Query("SELECT * FROM pending_feedback WHERE status = 'COMPLETED' AND updatedAt < :before")
    suspend fun getExpiredCompleted(before: Long): List<PendingFeedback>

    @Query("SELECT * FROM pending_feedback WHERE status = 'PERMANENT_FAILED' AND updatedAt < :before")
    suspend fun getExpiredFailed(before: Long): List<PendingFeedback>

    @Query("SELECT * FROM pending_feedback WHERE status = 'RETRY_EXHAUSTED' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getRetryExhausted(limit: Int): List<PendingFeedback>

    @Query("SELECT COUNT(*) FROM pending_feedback WHERE status IN ('PENDING_BODY', 'PENDING_ATTACHMENTS')")
    fun pendingCount(): Flow<Int>

    @Delete
    suspend fun delete(feedback: PendingFeedback)
}
