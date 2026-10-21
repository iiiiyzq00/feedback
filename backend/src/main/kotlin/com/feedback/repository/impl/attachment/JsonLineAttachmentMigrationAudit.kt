package com.feedback.repository.impl.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.attachment.AttachmentCleanupAuditOutcome
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import com.feedback.repository.attachment.AttachmentMigrationAudit
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant

/** JSONL 附件迁移审计实现；只保存不可逆摘要，不保存路径、对象键或凭据。 */
@Component
class JsonLineAttachmentMigrationAudit(private val properties: AttachmentObjectStorageProperties) : AttachmentMigrationAudit {
    private val writeLock = Any()

    /** 串行追加清理事件，避免并发写入产生交错 JSON 行。 */
    override fun recordCleanup(candidate: AttachmentObjectCleanupCandidatePo, outcome: AttachmentCleanupAuditOutcome) {
        append("""{"timestamp":"${Instant.now()}","attachmentId":${candidate.id},"bytes":${candidate.fileSizeBytes},"outcome":"${outcome.code}","pathSha256":"${digestText(candidate.storagePath)}","objectKeySha256":"${digestText(candidate.objectKey)}","contentSha256":"${candidate.fileSha256.toHex()}"}""")
    }

    /** 记录白名单根内源文件缺失分类，不将其伪装成迁移成功。 */
    override fun recordSourceUnavailable(candidate: AttachmentObjectMigrationCandidatePo) {
        append("""{"timestamp":"${Instant.now()}","attachmentId":${candidate.id},"bytes":${candidate.fileSizeBytes},"outcome":"SOURCE_UNAVAILABLE_CLASSIFIED","pathSha256":"${digestText(candidate.storagePath)}","contentSha256":"${candidate.fileSha256.toHex()}"}""")
    }

    /** 扫描运维审计文件并统计没有闭合结果的删除意图。 */
    override fun countUnclosedDeleteIntents(): Long {
        val auditPath = Path.of(properties.cleanupAuditPath).toAbsolutePath().normalize()
        if (!Files.exists(auditPath)) {
            return 0
        }
        val latestOutcome = mutableMapOf<Long, String>()
        synchronized(writeLock) {
            Files.lines(auditPath).use { lines ->
                lines.forEach { line ->
                    val match = AUDIT_EVENT_PATTERN.find(line)
                        ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_MIGRATION_AUDIT_MALFORMED")
                    latestOutcome[match.groupValues[1].toLong()] = match.groupValues[2]
                }
            }
        }
        return latestOutcome.values.count { it == AttachmentCleanupAuditOutcome.VERIFIED_DELETE_INTENT.code }.toLong()
    }

    /** 创建审计父目录并以追加模式写入单条完整 JSONL。 */
    private fun append(line: String) {
        val auditPath = Path.of(properties.cleanupAuditPath).toAbsolutePath().normalize()
        auditPath.parent?.let(Files::createDirectories)
        synchronized(writeLock) {
            Files.writeString(auditPath, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** 对路径或对象键生成不可逆 SHA-256 摘要。 */
    private fun digestText(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    /** 把摘要编码为稳定小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val AUDIT_EVENT_PATTERN = Regex(""""attachmentId":(\d+).*"outcome":"([A-Z_]+)"""")
    }
}
