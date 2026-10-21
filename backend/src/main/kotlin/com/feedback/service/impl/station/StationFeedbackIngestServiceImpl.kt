package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.dto.station.*
import com.feedback.service.masking.MaskingPipeline
import com.feedback.security.station.StationGatewayPrincipal
import com.feedback.service.station.StationFeedbackIngestService
import com.feedback.manager.station.StationFeedbackIngestCommitWorker
import com.feedback.service.station.StationOnlineIdentityService
import com.feedback.model.station.StationOnlineRawPayload
import com.feedback.service.station.StationRawPayloadService
import com.feedback.model.station.PreparedStationIngest
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/** 在线受理实现；当前写入事务保持既有原子语义，后续由提交 Worker 缩短持锁阶段。 */
@Service
class StationFeedbackIngestServiceImpl(
    private val objectMapper: ObjectMapper,
    private val masking: MaskingPipeline,
    private val identities: StationOnlineIdentityService,
    private val rawPayloads: StationRawPayloadService,
    private val commitWorker: StationFeedbackIngestCommitWorker
) : StationFeedbackIngestService {
    companion object {
        const val CHANNEL = "STATION_FEEDBACK"
        const val FIELD_MAPPING_VERSION = "station-map-v1"
        const val NORMALIZATION_VERSION = "station-norm-v1"
    }

    /** 在事务外完成校验、脱敏、JSON、指纹与加密，只把预备命令交给短事务提交器。 */
    override fun accept(
        request: StationFeedbackRequest,
        version: StationApiVersion,
        idempotencyKey: String?,
        principal: StationGatewayPrincipal?
    ): StationAcceptResult {
        val authenticatedPrincipal = principal
            ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED, "STATION_GATEWAY_PRINCIPAL_MISSING")
        val receipt = UUID.randomUUID()
        val receiptBytes = uuidBytes(receipt)
        val now = LocalDateTime.now()
        val fingerprint = requestFingerprint(request, version, authenticatedPrincipal.userId)
        val title = masking.mask(request.title.trim(), "station_title_${receipt}")
        val content = masking.mask(request.content, "station_content_${receipt}")
        val description = request.description.takeIf(String::isNotBlank)
            ?.let { masking.mask(it, "station_description_${receipt}") }
        val privacyStatus = listOfNotNull(title.maskingStatus, content.maskingStatus, description?.maskingStatus)
            .let { if (it.all { status -> status == "PASS" }) "PASS" else "REVIEW_NEEDED" }
        if (idempotencyKey != null && !idempotencyKey.matches(Regex("^[A-Za-z0-9._:-]{8,100}$"))) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_IDEMPOTENCY_KEY_INVALID")
        }
        val recordId = idempotencyKey ?: "fp:${Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint)}"
        val adapterResolvedPhone = if (authenticatedPrincipal.adapter) request.phone.trim().takeIf(String::isNotBlank) else null

        val faultTime = com.feedback.service.normalization.FaultOccurrenceTimeResolver.fromDescription(
            CHANNEL, listOfNotNull(content.maskedText, description?.maskedText).joinToString("\n"), now)
        val photos = request.photoUrlList?.takeIf { it.isNotEmpty() }?.let(objectMapper::writeValueAsString)
        val rawPayload = rawPayloads.prepare(rawPayload(request, version), now)
        val identity = adapterResolvedPhone?.let { identities.preparePhone(it, "ADAPTER_RESOLVED") }
        return commitWorker.commit(PreparedStationIngest(
            receiptId = receiptBytes,
            sourceRecordId = recordId,
            appVersion = request.appVersion.ifBlank { null },
            occurredAt = faultTime.occurredAt,
            faultTimeRaw = faultTime.raw,
            faultTimePrecision = faultTime.precision,
            faultTimeSource = faultTime.source,
            faultTimeResolution = faultTime.resolution,
            now = now,
            safeTitle = title.maskedText,
            safeContent = content.maskedText,
            safeDescription = description?.maskedText,
            maskingStatus = privacyStatus,
            sourceApiVersion = version.name,
            productId = request.productId.trim(),
            sourceAppId = request.appId.trim().ifBlank { null },
            sourceAppName = request.appName.trim().ifBlank { null },
            sourceUserId = request.userId.trim().ifBlank { authenticatedPrincipal.userId },
            authenticatedUserId = authenticatedPrincipal.userId,
            authenticatedProductCode = authenticatedPrincipal.productCode.takeIf(String::isNotBlank)
                ?: throw ClientException(ErrorCode.BAD_REQUEST, "STATION_UC_PRODUCT_CODE_MISSING"),
            sourcePlatform = request.platform.trim(),
            sourceFeedbackType = request.type.trim(),
            sourceSubChannel = request.channel.trim(),
            pendingPhotoUrls = photos,
            sourcePhoneCiphertext = if (authenticatedPrincipal.adapter) null else request.phone.takeIf {
                version == StationApiVersion.V2 && it.isNotBlank()
            },
            contactStatus = when {
                adapterResolvedPhone != null -> "ENCRYPTED"
                request.phone.isBlank() -> "NOT_PROVIDED"
                else -> "NOT_PROCESSED"
            },
            requestFingerprint = fingerprint,
            rawPayload = rawPayload,
            identity = identity
        ))
    }

    /** 构造兼容既有密文 JSON schema 的原始载荷。 */
    private fun rawPayload(request: StationFeedbackRequest, version: StationApiVersion) = StationOnlineRawPayload(
        apiVersion = version.name,
        title = request.title,
        content = request.content,
        description = request.description,
        productId = request.productId,
        appVersion = request.appVersion,
        phone = request.phone,
        platform = request.platform,
        type = request.type,
        channel = request.channel,
        userId = request.userId,
        appId = request.appId,
        appName = request.appName,
        photoUrlList = request.photoUrlList ?: emptyList()
    )

    /** 计算排除动态扩展属性的稳定命令指纹，保证同键异命令冲突。 */
    internal fun requestFingerprint(request: StationFeedbackRequest, version: StationApiVersion, actor: String?): ByteArray {
        val canonical = objectMapper.createObjectNode().apply {
            put("apiVersion", version.name)
            put("actor", actor.orEmpty())
            put("productId", request.productId.trim())
            put("appVersion", request.appVersion.trim())
            put("title", request.title.trim())
            put("content", request.content.trim())
            if (version == StationApiVersion.V2) {
                put("phone", request.phone.trim())
            }
            put("platform", request.platform.trim())
            put("type", request.type.trim())
            put("channel", request.channel.trim())
            put("userId", request.userId.trim())
            put("description", request.description.trim())
            put("appId", request.appId.trim())
            put("appName", request.appName.trim())
            set<com.fasterxml.jackson.databind.JsonNode>("photoUrlList", objectMapper.valueToTree(request.photoUrlList ?: emptyList<String>()))
        }
        return MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(canonical))
    }
    /** 把 UUID 转为兼容既有 BINARY(16) 的字节序。 */
    private fun uuidBytes(uuid: UUID): ByteArray {
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }
}
