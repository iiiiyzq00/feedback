package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.MaintenanceAttachmentMapper
import com.feedback.model.po.scheduler.MaintenanceAttachmentCandidatePo
import org.springframework.stereotype.Repository

/** 已过期附件的候选读取和删除状态写入。 */
@Repository
class AttachmentSensitiveRetentionRepository(
    private val mapper: MaintenanceAttachmentMapper
) {
    fun candidates(limit: Int): List<MaintenanceAttachmentCandidatePo> = mapper.candidates(limit)
    fun markDeleted(ids: Collection<Long>): Int = ids.chunked(RETENTION_BATCH_SIZE).sumOf(mapper::markDeleted)
}
