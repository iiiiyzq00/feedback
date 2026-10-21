package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.SafeErrorDetail
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ServiceException
import com.feedback.config.StationDualWriteProperties
import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.model.po.station.online.StationDualWriteTaskPo
import com.feedback.repository.station.online.StationDualWriteTaskRepository
import com.feedback.security.station.StationGatewayPrincipal
import com.feedback.service.masking.EnvelopeCrypto
import com.feedback.model.station.StationDualWriteAcceptResult
import com.feedback.model.station.StationDualWriteEnvelope
import com.feedback.model.station.StationDualWriteTask
import com.feedback.service.station.StationDualWriteTaskService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/** 双写任务实现；加解密和 JSON 处理位于事务外，事务只保护认领与状态推进。 */
@Service
class StationDualWriteTaskServiceImpl(
    private val repository: StationDualWriteTaskRepository,
    private val mapper: ObjectMapper,
    private val crypto: EnvelopeCrypto,
    private val properties: StationDualWriteProperties,
    private val transactions: TransactionTemplate
) : StationDualWriteTaskService {
    private val key by lazy {
        if (properties.storageSecret.length < 32) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_DUAL_WRITE_STORAGE_SECRET_INVALID")
        }
        MessageDigest.getInstance("SHA-256").digest(
            ("feedback-platform/station/dual-write/v1\u0000" + properties.storageSecret).toByteArray(Charsets.UTF_8)
        )
    }

    /** 事务外加密，短事务内按幂等键写入并复核命令指纹。 */
    override fun accept(envelope: StationDualWriteEnvelope): StationDualWriteAcceptResult {
        if (!properties.ready()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_DUAL_WRITE_CONFIGURATION_INVALID")
        }
        val plain = mapper.writeValueAsBytes(envelope)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(plain)
        val encrypted = try { crypto.encrypt(plain, key) } finally { plain.fill(0) }
        val receipt = UUID.randomUUID()
        val row = StationDualWriteTaskPo(
                receiptId = uuidBytes(receipt),
                idempotencyKey = envelope.idempotencyKey,
                requestFingerprint = fingerprint,
                apiVersion = envelope.version.name,
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                payloadTag = encrypted.tag,
                keyId = properties.storageKeyId
            )
        // 加密在事务外完成；事务只覆盖幂等 upsert 与指纹一致性检查。
        val persisted = transactions.execute {
            repository.insertOrLocate(row).also {
                if (!MessageDigest.isEqual(it.requestFingerprint, fingerprint)) {
                    throw ServiceException(ErrorCode.STATION_IDEMPOTENCY_CONFLICT, "STATION_IDEMPOTENCY_CONFLICT")
                }
            }
        } ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_DUAL_WRITE_TRANSACTION_EMPTY")
        return StationDualWriteAcceptResult(encodeReceipt(persisted.receiptId), persisted.id)
    }

    /** 短事务认领单条任务，提交后再解密载荷。 */
    override fun claimOne(workerId: String): StationDualWriteTask? {
        // 行锁只保护任务认领；解密和 JSON 反序列化不占用数据库事务。
        val task = transactions.execute { repository.claimOne(workerId) } ?: return null
        val plain = crypto.decrypt(task.payloadCiphertext, task.payloadTag, task.payloadNonce, key)
        val envelope = try { mapper.readValue(plain, StationDualWriteEnvelope::class.java) } finally { plain.fill(0) }
        return StationDualWriteTask(
            task.id, envelope, task.oldFbpStatus == "SUCCEEDED", task.newPlatformStatus == "SUCCEEDED",
            task.retryCount, task.maxRetryCount
        )
    }

    /** 以租约 owner 条件推进旧平台分支成功状态。 */
    override fun markOldSucceeded(id: Long, workerId: String) = repository.markOldSucceeded(id, workerId)

    /** 以租约 owner 条件推进整体完成并关联新平台 source。 */
    override fun markCompleted(id: Long, workerId: String, sourceId: Long) = repository.markCompleted(id, workerId, sourceId)

    /** 仅按治理异常的 retryable 属性有限重试，并保存安全错误摘要。 */
    override fun markFailure(task: StationDualWriteTask, workerId: String, error: Throwable) {
        val retry = task.retryCount + 1
        val governed = error as? AbstractException
        val retryable = governed?.errorCode?.retryable ?: true
        val final = !retryable || retry >= task.maxRetryCount
        val delay = (5L shl (retry - 1).coerceIn(0, 9)).coerceAtMost(1800)
        val errorCode = governed?.errorCode?.name ?: ErrorCode.INTERNAL_ERROR.name
        repository.markFailure(
            task.id, workerId,
            if (final) "FAILED_FINAL" else "RETRY_PENDING",
            retry,
            if (final) "FAILED_FINAL" else "RETRY_PENDING",
            final,
            delay,
            errorCode,
            SafeErrorDetail.summary(error, "STATION_DUAL_WRITE")
        )
    }

    /** 将 UUID 转换为既有 BINARY(16) 编码。 */
    private fun uuidBytes(uuid: UUID) = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    /** 将回执转换为既有 URL-safe Base64 编码。 */
    private fun encodeReceipt(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
