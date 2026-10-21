package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.MaintenanceAiTaskMapper
import com.feedback.mapper.scheduler.MaintenanceAuthorizedContactMapper
import com.feedback.mapper.scheduler.MaintenanceImportAttachmentTaskMapper
import com.feedback.mapper.scheduler.MaintenanceImportRowMapper
import com.feedback.mapper.scheduler.MaintenanceUploadChunkMapper
import org.springframework.stereotype.Repository

/** 导入原始行、上传会话、AI 快照、授权联系方式和附件 URL 的清理。 */
@Repository
class ImportSensitiveRetentionRepository(
    private val rows: MaintenanceImportRowMapper,
    private val chunks: MaintenanceUploadChunkMapper,
    private val aiTasks: MaintenanceAiTaskMapper,
    private val contacts: MaintenanceAuthorizedContactMapper,
    private val attachmentTasks: MaintenanceImportAttachmentTaskMapper
) {
    fun clearImportRows() = rows.clearExpiredRawRows()
    fun clearAiSnapshots() = aiTasks.clearExpiredSnapshots()
    fun clearExpiredContacts() = contacts.clearExpired()
    fun clearFinishedOrExpiredUrls() = attachmentTasks.clearFinishedOrExpiredUrls()
    fun expireUploadSessions(): Pair<Int, Int> {
        var deletedChunks = 0
        var expiredSessions = 0
        while (true) {
            val ids = rows.expiredUploadSessionIds(RETENTION_BATCH_SIZE)
            if (ids.isEmpty()) break
            deletedChunks += chunks.deleteBySessionIds(ids)
            expiredSessions += rows.expireUploadSessions(ids)
        }
        return deletedChunks to expiredSessions
    }
}
