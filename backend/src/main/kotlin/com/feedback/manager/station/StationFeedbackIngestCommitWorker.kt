package com.feedback.manager.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.dto.station.StationAcceptResult
import com.feedback.model.po.station.online.StationOnlineChannelPo
import com.feedback.model.po.station.online.StationOnlineContentPo
import com.feedback.model.po.station.online.StationOnlineSourcePo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.repository.station.online.StationOnlineContentRepository
import com.feedback.repository.station.online.StationOnlineSourceRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Base64

/** Station 在线受理短事务提交器；不执行 JSON、脱敏、加密或外部 I/O。 */
@Component
class StationFeedbackIngestCommitWorker(
    private val sources: StationOnlineSourceRepository,
    private val contents: StationOnlineContentRepository,
    private val channels: StationOnlineChannelRepository,
    private val identities: StationOnlineIdentityService,
    private val rawPayloads: StationRawPayloadService,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) {
    /** 原子写入受理事实；唯一键定位后重新核对指纹并稳定重放。 */
    @Transactional
    fun commit(command: PreparedStationIngest): StationAcceptResult {
        val source = StationOnlineSourcePo(
            publicId = command.receiptId,
            sourceRecordId = command.sourceRecordId,
            appVersion = command.appVersion,
            sourceEventTime = command.now,
            faultOccurredAt = command.occurredAt,
            faultTimeRaw = command.faultTimeRaw,
            faultTimePrecision = command.faultTimePrecision,
            faultTimeSource = command.faultTimeSource,
            faultTimeResolution = command.faultTimeResolution,
            sourceUpdateTime = command.now,
            ingestTime = command.now
        )
        val affected = sources.insertOrLocate(source)
        channels.find(source.id)?.let { existing ->
            if (!java.security.MessageDigest.isEqual(existing.requestFingerprint, command.requestFingerprint)) {
                throw ServiceException(ErrorCode.STATION_IDEMPOTENCY_CONFLICT, "STATION_IDEMPOTENCY_CONFLICT")
            }
            rawPayloads.storePrepared(source.id, command.rawPayload)
            return StationAcceptResult(encodeReceipt(existing.receiptId), source.id)
        }
        if (affected != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_SOURCE_WITHOUT_CHANNEL")
        }
        contents.insert(StationOnlineContentPo(
            sourceId = source.id,
            title = command.safeTitle,
            description = command.safeContent,
            mappingRuleVersion = "station-map-v1",
            normalizationVersion = "station-norm-v1",
            processingStatus = "OPEN",
            maskingStatus = command.maskingStatus
        ))
        channels.insert(StationOnlineChannelPo(
            sourceId = source.id,
            receiptId = command.receiptId,
            sourceApiVersion = command.sourceApiVersion,
            productId = command.productId,
            sourceAppId = command.sourceAppId,
            sourceAppName = command.sourceAppName,
            sourceUserId = command.sourceUserId,
            authenticatedUserId = command.authenticatedUserId,
            authenticatedProductCode = command.authenticatedProductCode,
            sourcePlatform = command.sourcePlatform,
            sourceFeedbackType = command.sourceFeedbackType,
            sourceSubChannel = command.sourceSubChannel,
            sourceDescription = command.safeDescription,
            pendingPhotoUrls = command.pendingPhotoUrls,
            sourcePhoneCiphertext = command.sourcePhoneCiphertext,
            contactStatus = command.contactStatus,
            nextRetryAt = command.now,
            requestFingerprint = command.requestFingerprint,
            createdAt = command.now,
            updatedAt = command.now
        ))
        val identityStored = command.identity?.let { identities.storePrepared(source.id, it) } == true
        rawPayloads.storePrepared(source.id, command.rawPayload)
        dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(source.id), FeedbackListProjectionDirtyReason.CHANNEL))
        if (identityStored) {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(source.id), FeedbackListProjectionDirtyReason.IDENTITY))
        }
        return StationAcceptResult(encodeReceipt(command.receiptId), source.id)
    }

    /** 将 16 字节收据编码为既有 URL-safe HTTP/RPC 表示。 */
    private fun encodeReceipt(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
