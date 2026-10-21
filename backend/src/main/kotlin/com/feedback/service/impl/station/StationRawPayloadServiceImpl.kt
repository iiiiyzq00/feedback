package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.station.online.StationOnlineRawPayloadPo
import com.feedback.repository.station.online.StationOnlineRawPayloadRepository
import com.feedback.service.masking.EnvelopeCrypto
import com.feedback.service.station.StationRawPayloadService
import com.feedback.model.station.PreparedStationRawPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
/** Station 原始载荷服务实现；事务外完成加解密，持久化阶段只处理受控密文模型。 */
class StationRawPayloadServiceImpl(
    @Value("\${app.station-feedback.raw-storage-secret}") secret: String,
    private val crypto: EnvelopeCrypto,
    private val mapper: ObjectMapper,
    private val repository: StationOnlineRawPayloadRepository,
    @Value("\${app.station-feedback.raw-retention-days:30}") private val retentionDays: Long,
    @Value("\${app.station-feedback.raw-storage-key-id:station-raw-v1}") private val keyId: String
) : StationRawPayloadService {
    private val key = MessageDigest.getInstance("SHA-256").digest(
        ("feedback-platform/station/raw/v1\u0000" + secret.also {
            if (it.length < 32) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_RAW_STORAGE_SECRET_INVALID")
            }
        }).toByteArray(Charsets.UTF_8)
    )

    /** 在事务外序列化并加密载荷，明文字节在离开方法前清零。 */
    override fun prepare(payload: Any, now: LocalDateTime): PreparedStationRawPayload {
        val plain = mapper.writeValueAsBytes(payload)
        val encrypted = try { crypto.encrypt(plain, key) } finally { plain.fill(0) }
        return PreparedStationRawPayload(
            payloadCiphertext = encrypted.ciphertext,
            payloadNonce = encrypted.nonce,
            payloadTag = encrypted.tag,
            keyId = keyId,
            retentionUntil = now.plusDays(retentionDays)
        )
    }

    /** 只把预加密写模型映射到 MyBatis PO，适合在短事务中调用。 */
    override fun storePrepared(sourceId: Long, payload: PreparedStationRawPayload) {
        repository.upsert(StationOnlineRawPayloadPo(
            sourceId = sourceId,
            payloadCiphertext = payload.payloadCiphertext,
            payloadNonce = payload.payloadNonce,
            payloadTag = payload.payloadTag,
            keyId = payload.keyId,
            retentionUntil = payload.retentionUntil
        ))
    }

    /** 兼容既有调用；调用方应确保本方法不处于写事务内。 */
    override fun store(sourceId: Long, payload: Any, now: LocalDateTime) {
        storePrepared(sourceId, prepare(payload, now))
    }

    /** 解密单条原始载荷；过期或不存在时返回稳定未找到错误。 */
    override fun inspect(sourceId: Long): JsonNode = inspectMany(listOf(sourceId))[sourceId]
        ?: throw ClientException(ErrorCode.NOT_FOUND, "MASKING_RAW_EXPIRED_OR_UNAVAILABLE")

    /** 有界批量解密受控原始载荷，任一损坏密文使调用失败关闭。 */
    override fun inspectMany(sourceIds: List<Long>): Map<Long, JsonNode> = repository.findBySourceIds(sourceIds).associate { row ->
        val plain = crypto.decrypt(row.payloadCiphertext, row.payloadTag, row.payloadNonce, key)
        row.sourceId to try { mapper.readTree(plain) } finally { plain.fill(0) }
    }

    /** 批量恢复时隔离单条损坏密文；无法确认 sourceMode 的记录不会进入 Online 恢复写路径。 */
    override fun inspectManySafely(sourceIds: List<Long>): Map<Long, JsonNode> = repository.findBySourceIds(sourceIds)
        .mapNotNull { row ->
            runCatching {
                val plain = crypto.decrypt(row.payloadCiphertext, row.payloadTag, row.payloadNonce, key)
                row.sourceId to try { mapper.readTree(plain) } finally { plain.fill(0) }
            }.getOrNull()
        }
        .toMap()
}
