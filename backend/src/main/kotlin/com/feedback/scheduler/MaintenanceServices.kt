package com.feedback.scheduler

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.audit.SystemAuditLog
import com.feedback.repository.SystemAuditLogRepository
import com.feedback.repository.scheduler.MaintenanceLeaseRepository
import com.feedback.repository.scheduler.SensitiveRetentionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/** 通过共享的不可变系统审计边界写入维护结果。 */
@Service
class MaintenanceAuditRecorder(
    private val audits: SystemAuditLogRepository,
    private val json: ObjectMapper,
) {
    fun record(operation: String, target: String, detail: Map<String, Any?>) {
        audits.save(
            SystemAuditLog(
                operation = operation,
                targetType = "MAINTENANCE",
                targetId = target,
                operationDetail = json.valueToTree(detail),
                ipAddress = null,
            )
        )
    }
}

/** 回收被遗弃的导入与 AI 租约，但不改变它们原有的重试状态机。 */
@Service
class StaleLeaseRecovery(
    private val repository: MaintenanceLeaseRepository,
    private val audit: MaintenanceAuditRecorder,
) {
    @Transactional
    fun recover(): Map<String, Int> {
        val result = mapOf(
            "importBatches" to repository.recoverImports(),
            "aiTasks" to repository.recoverAiTasks(),
        )
        audit.record("STALE_LEASE_RECOVERY", "WORKERS", result)
        return result
    }
}

/**
 * 清除过期的敏感载荷，同时保留原有的排除规则、路径限制、
 * 状态迁移与审计闭环。跨表决策只依据可串行化事务内
 * 有界的单表读取；更新依旧是单表 MyBatis 语句。
 */
@Service
class CipherCleanupTask(
    private val repository: SensitiveRetentionRepository,
    private val audit: MaintenanceAuditRecorder,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir: String,
) {
    private val root = Path.of(attachmentDir).toAbsolutePath().normalize()

    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun cleanup(): Map<String, Int> {
        val sdk = clearSdkEnvelopes()
        val importRows = repository.clearImportRows()
        val stationPayloads = clearStationPayloads()
        val aiSnapshots = repository.clearAiSnapshots()

        // 规范诊断标识是加密的长期事实，不会被常规的保留期维护抹掉；
        // 生命周期变更必须走受治理的密钥退役流程。
        val contacts = 0
        val stagedContacts = repository.clearExpiredContacts()
        val importUrls = repository.clearFinishedOrExpiredUrls()
        val (expiredChunkRows, expiredChunkSessions) = repository.expireUploadSessions()
        val attachments = clearAttachmentFiles()

        val result = linkedMapOf(
            "sdkEnvelopes" to sdk,
            "importRows" to importRows,
            "stationPayloads" to stationPayloads,
            "aiSnapshots" to aiSnapshots,
            "contacts" to contacts,
            "stagedContacts" to stagedContacts,
            "importUrls" to importUrls,
            "expiredChunkRows" to expiredChunkRows,
            "expiredChunkSessions" to expiredChunkSessions,
            "attachments" to attachments,
        )
        audit.record("CIPHER_RETENTION_CLEANUP", "SENSITIVE_DATA", result)
        return result
    }

    private fun clearSdkEnvelopes(): Int {
        var afterId: Long? = null
        var changed = 0
        while (true) {
            val candidates = repository.sdkCandidates(afterId, BATCH_SIZE)
            if (candidates.isEmpty()) break
            val sourceIds = candidates.mapNotNull { it.feedbackSourceId }
            val protected = repository.activeAlertSourceIds(sourceIds)
            val eligible = candidates.filter { it.feedbackSourceId == null || it.feedbackSourceId !in protected }
                .map { it.id }
            if (eligible.isNotEmpty()) changed += repository.clearSdk(eligible)
            afterId = candidates.last().id
            if (candidates.size < BATCH_SIZE) break
        }
        return changed
    }

    private fun clearStationPayloads(): Int {
        var afterId: Long? = null
        var changed = 0
        while (true) {
            val candidates = repository.stationCandidates(afterId, BATCH_SIZE)
            if (candidates.isEmpty()) break
            val statuses = repository.maskingStatuses(candidates.map { it.sourceId })
            val eligible = candidates.filter { statuses[it.sourceId] !in BLOCKING_MASKING_STATUSES }.map { it.id }
            if (eligible.isNotEmpty()) changed += repository.clearStation(eligible)
            afterId = candidates.last().id
            if (candidates.size < BATCH_SIZE) break
        }
        return changed
    }

    private fun clearAttachmentFiles(): Int {
        val candidates = repository.attachmentCandidates(BATCH_SIZE)
        candidates.forEach { row ->
            val path = row.storagePath?.let(Path::of)?.toAbsolutePath()?.normalize()
            if (path != null && !path.startsWith(root)) throw SecurityException("ATTACHMENT_PATH_OUTSIDE_ROOT")
            if (path != null) Files.deleteIfExists(path)
        }
        return if (candidates.isEmpty()) 0 else repository.markAttachmentsDeleted(candidates.map { it.id })
    }

    private companion object {
        const val BATCH_SIZE = 500
        val BLOCKING_MASKING_STATUSES = setOf("PENDING", "REVIEW_NEEDED")
    }
}
