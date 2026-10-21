package com.feedback.sdk.db.dao

import androidx.room.*
import com.feedback.sdk.db.entity.AttachmentRecord

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentRecord)

    @Update
    suspend fun update(attachment: AttachmentRecord)

    @Query("SELECT * FROM attachment_record WHERE clientFeedbackId = :feedbackId")
    suspend fun getByFeedbackId(feedbackId: String): List<AttachmentRecord>

    @Query("SELECT * FROM attachment_record WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<AttachmentRecord>

    @Query("SELECT * FROM attachment_record WHERE clientFeedbackId = :feedbackId AND status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingByFeedbackId(feedbackId: String): List<AttachmentRecord>

    /** 进程或 Worker 可能在一行被标记为 UPLOADING 之后死掉；SDK 初始化时让它重新可领取。 */
    @Query("UPDATE attachment_record SET status = 'PENDING' WHERE status = 'UPLOADING'")
    suspend fun resetInterruptedUploads()

    @Query("""
        SELECT DISTINCT a.clientFeedbackId
        FROM attachment_record a
        INNER JOIN pending_feedback f ON f.clientFeedbackId = a.clientFeedbackId
        WHERE a.status = 'PENDING' AND f.status = 'PENDING_ATTACHMENTS' AND f.receiptId IS NOT NULL
    """)
    suspend fun getFeedbackIdsReadyForUpload(): List<String>

    @Query("SELECT COUNT(*) FROM attachment_record WHERE clientFeedbackId = :feedbackId AND status != 'UPLOADED'")
    suspend fun countIncomplete(feedbackId: String): Int

    @Query("SELECT SUM(sizeBytes) FROM attachment_record WHERE status IN ('PENDING', 'UPLOADING')")
    suspend fun getPendingTotalSize(): Long

    @Query("SELECT * FROM attachment_record WHERE status = 'FAILED' AND retryCount >= 8")
    suspend fun getExhaustedRetries(): List<AttachmentRecord>

    @Delete
    suspend fun delete(attachment: AttachmentRecord)
}
