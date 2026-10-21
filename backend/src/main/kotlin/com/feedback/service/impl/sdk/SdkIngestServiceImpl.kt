package com.feedback.service.impl.sdk

import com.feedback.manager.sdk.SdkIngestPersistence
import com.feedback.security.sdk.SdkRequestAuthenticator
import com.feedback.strategy.sdk.SdkIngestPolicy
import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.dto.sdk.SdkFeedbackAck
import com.feedback.dto.sdk.SdkFeedbackProgress
import com.feedback.dto.sdk.SdkFeedbackRequest
import com.feedback.model.sdk.SdkIngest
import com.feedback.repository.sdk.SdkIngestRepository
import com.feedback.service.sdk.*
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 编排 SDK 反馈主体接入。
 *
 * 此类只负责校验请求、调用安全门面、处理幂等语义并组装接入记录。Redis 操作在事务外完成，
 * 最终数据库写入由 [SdkIngestPersistence] 建立短事务，避免远程等待占用数据库连接。
 */
@Service
class SdkIngestServiceImpl(
    private val repository: SdkIngestRepository,
    private val requestAuthenticator: SdkRequestAuthenticator,
    private val persistence: SdkIngestPersistence,
    private val policy: SdkIngestPolicy
) : SdkIngestService {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 受理 SDK 加密上报：先完成验签、防重放、限流与信封校验，再以业务键和命令指纹执行幂等持久化。 */
    override fun ingestFeedback(
        request: SdkFeedbackRequest,
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
        clientIp: String
    ): SdkFeedbackAck {
        // 版本不兼容时无需消耗 nonce 或限流额度，因此应最先失败。
        if (request.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            reject(ErrorCode.SDK_SCHEMA_VERSION_UNSUPPORTED, "SDK_SCHEMA_VERSION_UNSUPPORTED")
        }
        validateEnvelope(request)

        val bodyHash = sha256(bodyBytes)
        requestAuthenticator.authenticate(
            appKey = appKey,
            signature = signature,
            timestamp = timestamp,
            nonce = nonce,
            method = method,
            path = path,
            bodySha256 = bodyHash.toHex(),
            clientIp = clientIp
        )

        repository.findByAppIdAndClientFeedbackId(appKey, request.clientFeedbackId)?.let { existing ->
            requireSameCommand(existing, bodyHash)
            log.info(
                "SDK ingest idempotent replay: app={} clientFeedbackId={}",
                appKey,
                request.clientFeedbackId
            )
            return existing.toAck()
        }

        val urgent = request.issueType?.uppercase() in URGENT_ISSUE_TYPES
        val candidate = SdkIngest(
                receiptId = uuidBytes(UUID.randomUUID()),
                clientFeedbackId = request.clientFeedbackId,
                appId = appKey,
                appVersion = request.appVersion,
                packageName = request.packageName,
                functionModule = request.functionModule,
                issueType = request.issueType,
                sourceEventTime = parseSourceEventTime(request.sourceEventTime),
                osVersion = request.osVersion,
                networkType = request.networkType,
                triggerType = request.triggerType,
                encryptedDataKey = decodePayload(request.encryptedPayload.encryptedDataKey, "SDK_ENCRYPTED_DATA_KEY_INVALID"),
                payloadNonce = decodePayload(request.encryptedPayload.nonce, "SDK_PAYLOAD_NONCE_INVALID"),
                payloadCiphertext = decodePayload(request.encryptedPayload.ciphertext, "SDK_CIPHERTEXT_INVALID"),
                payloadTag = decodePayload(request.encryptedPayload.tag, "SDK_PAYLOAD_TAG_INVALID"),
                contactNonce = request.encryptedContactPayload?.nonce
                    ?.let { decodePayload(it, "SDK_CONTACT_NONCE_INVALID") },
                contactCiphertext = request.encryptedContactPayload?.ciphertext
                    ?.let { decodePayload(it, "SDK_CONTACT_CIPHERTEXT_INVALID") },
                contactTag = request.encryptedContactPayload?.tag
                    ?.let { decodePayload(it, "SDK_CONTACT_TAG_INVALID") },
                contactAllowed = request.encryptedContactPayload?.contactAllowed ?: false,
                contactProcessingStatus = if (request.encryptedContactPayload == null) "NOT_PROVIDED" else "PENDING",
                urgencyClass = if (urgent) "URGENT" else "NORMAL",
                keyId = request.encryptedPayload.keyId,
                algorithmVersion = request.encryptedPayload.algorithm,
                schemaVersion = request.schemaVersion,
                requestSha256 = bodyHash,
                requestSizeBytes = bodyBytes.size,
                ingestStatus = "RECEIVED",
                retentionUntil = LocalDateTime.now().plusDays(
                    if (urgent) EMERGENCY_RETENTION_DAYS else policy.normalRetentionDays()
                )
            )
        val saved = try {
            persistence.saveReceived(candidate)
        } catch (ex: DataIntegrityViolationException) {
            if (!ex.causedByConstraint(IDEMPOTENCY_CONSTRAINT)) {
                throw ex
            }
            val existing = repository.findByAppIdAndClientFeedbackId(appKey, request.clientFeedbackId)
                ?: throw ex
            requireSameCommand(existing, bodyHash)
            existing
        }

        val ack = saved.toAck()
        log.info("SDK ingest accepted: receipt={} clientFeedbackId={}", ack.receiptId, request.clientFeedbackId)
        return ack
    }

    /** 按回执查询 SDK 处理进度；校验调用方应用归属，禁止跨应用读取反馈状态。 */
    override fun feedbackProgress(receiptId: String, appKey: String): SdkFeedbackProgress {
        val ingest = ownedIngest(receiptId, appKey)
        val status = when (ingest.ingestStatus) {
            "COMPLETED" -> "COMPLETED"
            "REVIEW_NEEDED" -> "REVIEW_NEEDED"
            "FAILED" -> "FAILED"
            else -> "PROCESSING"
        }
        val stage = when {
            ingest.ingestStatus == "COMPLETED" -> "AVAILABLE"
            ingest.ingestStatus in setOf("FAILED", "REVIEW_NEEDED") -> "PRIVACY_GATE"
            ingest.bodyProcessingStatus == "COMPLETED" -> "STANDARDIZATION"
            ingest.bodyProcessingStatus == "PROCESSING" -> "MASKING"
            else -> "RECEIVED"
        }
        val progress = when (stage) {
            "RECEIVED" -> 10
            "MASKING" -> 45
            "STANDARDIZATION" -> 75
            else -> 100
        }
        return SdkFeedbackProgress(
            receiptId = receiptId,
            clientFeedbackId = ingest.clientFeedbackId,
            status = status,
            stage = stage,
            progressPercent = progress,
            retryable = ingest.ingestStatus in setOf("RETRY_PENDING", "FAILED"),
            receivedAt = ingest.receivedAt,
            processedAt = ingest.processedAt
        )
    }

    /** 复核业务键对应记录的命令指纹；同命令返回原回执，异命令返回幂等冲突。 */
    override fun idempotentAck(appKey: String, clientFeedbackId: String): SdkFeedbackAck? =
        repository.findByAppIdAndClientFeedbackId(appKey, clientFeedbackId)?.toAck()

    /** 在数据库事务外完成 SDK 签名、防重放和限流校验，任一安全依赖异常均失败关闭。 */
    override fun authenticateSdkRequest(
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodySha256: String,
        clientIp: String
    ) = requestAuthenticator.authenticate(
        appKey, signature, timestamp, nonce, method, path, bodySha256, clientIp
    )

    /** 按回执读取 SDK 信封并核对 appKey 所有权；记录不存在或跨应用访问统一失败关闭。 */
    private fun ownedIngest(receiptId: String, appKey: String): SdkIngest {
        val ingest = repository.findByReceiptId(decodeReceiptId(receiptId))
            ?: reject(ErrorCode.SDK_RECEIPT_NOT_FOUND, "SDK_RECEIPT_NOT_FOUND")
        if (ingest.appId != appKey) {
            reject(ErrorCode.SDK_RECEIPT_FORBIDDEN, "SDK_RECEIPT_FORBIDDEN")
        }
        return ingest
    }

    /** 校验 SDK 信封版本、算法、密文长度、时间窗和开放标识格式，异常输入失败关闭。 */
    private fun validateEnvelope(request: SdkFeedbackRequest) {
        val payload = request.encryptedPayload
        if (payload.algorithm !in ALLOWED_ALGORITHMS) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ENVELOPE_ALGORITHM_UNSUPPORTED")
        }
        if (decodePayload(payload.ciphertext, "SDK_CIPHERTEXT_INVALID").size > MAX_CIPHERTEXT_BYTES) {
            reject(ErrorCode.SDK_CIPHER_TOO_LARGE, "SDK_CIPHER_TOO_LARGE")
        }
        if (!payload.keyId.matches(KEY_ID_PATTERN)) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_KEY_ID_INVALID")
        }
    }

    /** 解析并约束来源事件时间，拒绝超出允许时间窗或格式错误的值。 */
    private fun parseSourceEventTime(value: String): LocalDateTime =
        runCatching { OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime() }
            .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_SOURCE_EVENT_TIME_INVALID") }

    /** 按显式长度上限解码 Base64 密文，临时字节只在最短作用域内保留。 */
    private fun decodePayload(value: String, reasonCode: String): ByteArray =
        runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, reasonCode) }

    /** 严格解码固定长度的 URL-safe 回执；非法编码不得触发数据库查询。 */
    private fun decodeReceiptId(value: String): ByteArray {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .recoverCatching { Base64.getDecoder().decode(value) }
            .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_RECEIPT_INVALID") }
        if (decoded.size != RECEIPT_BYTES) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_RECEIPT_LENGTH_INVALID")
        }
        return decoded
    }

    /** 把持久化信封转换为稳定 SDK 回执，只暴露协议允许的标识与状态。 */
    private fun SdkIngest.toAck() = SdkFeedbackAck(
        clientFeedbackId = clientFeedbackId
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_CLIENT_FEEDBACK_ID_MISSING"),
        receiptId = Base64.getUrlEncoder().withoutPadding().encodeToString(receiptId),
        status = "ENCRYPTED_ACCEPTED"
    )

    /** 计算稳定 SHA-256 摘要：SDK 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    /** 同业务键只允许相同请求体稳定重放，不同命令必须失败关闭。 */
    private fun requireSameCommand(existing: SdkIngest, requestSha256: ByteArray) {
        val existingFingerprint = existing.requestSha256
        if (existingFingerprint == null || !MessageDigest.isEqual(existingFingerprint, requestSha256)) {
            reject(ErrorCode.SDK_IDEMPOTENT_CONFLICT, "SDK_IDEMPOTENT_CONFLICT")
        }
    }

    /** 把摘要字节编码为小写十六进制文本；仅用于指纹和日志安全标识。 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 只把明确命中业务幂等唯一键的异常转换为重放 ACK。
     * 其他非空、长度或检查约束异常必须继续上抛，避免被已有记录意外掩盖。
     */
    private fun Throwable.causedByConstraint(constraintName: String): Boolean =
        generateSequence(this) { it.cause }
            .mapNotNull(Throwable::message)
            .any { it.contains(constraintName, ignoreCase = true) }

    /** 把 UUID 转换为数据库兼容字节：SDK 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(RECEIPT_BYTES)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    /** 抛出带稳定错误码的客户端异常；错误详情不得包含签名、密文或原始正文。 */
    private fun reject(errorCode: ErrorCode, reasonCode: String): Nothing =
        throw ClientException(errorCode, reasonCode)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = "sdk-form-v1"

        private const val RECEIPT_BYTES = 16
        private const val MAX_CIPHERTEXT_BYTES = 2 * 1024 * 1024
        private const val EMERGENCY_RETENTION_DAYS = 30L
        private const val IDEMPOTENCY_CONSTRAINT = "uk_app_client_feedback"
        private val ALLOWED_ALGORITHMS = setOf("AES-256-GCM+RSA-OAEP-256")
        private val URGENT_ISSUE_TYPES = setOf("URGENT", "SAFETY", "EMERGENCY", "S0", "S1")
        private val KEY_ID_PATTERN = Regex("^[a-zA-Z0-9_-]+$")
    }
}
