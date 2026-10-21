package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.security.WebPrincipal
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.service.station.StationOnlineRetryService
import com.feedback.model.station.StationRetryRequest
import com.feedback.model.station.StationRetryResponse
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import java.util.UUID

/** Station Online retry 写入口；行锁、批量重置、审计与列表投影标脏共享一个事务。 */
@Service
class StationOnlineRetryServiceImpl(
    private val channels: StationOnlineChannelRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val audits: BusinessAuditService
) : StationOnlineRetryService {
    /** 锁定最多 100 个可重试回执，批量重置、标脏和审计必须共同提交。 */
    @Transactional
    override fun retry(request: StationRetryRequest): StationRetryResponse {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED, "AUTHENTICATION_REQUIRED")
        if (request.receiptIds.size !in 1..100) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_RETRY_SIZE_INVALID")
        }
        val receipts = request.receiptIds.distinct().map(::decodeReceipt)
        val retryable = channels.findRetryableByReceiptsForUpdate(receipts)
        val sourceIds = retryable.map { it.sourceId }
        if (sourceIds.isNotEmpty()) {
            channels.resetRetryable(sourceIds)
            dirtyMarker.mark(
                FeedbackListProjectionDirtyCommand(sourceIds, FeedbackListProjectionDirtyReason.CHANNEL)
            )
        }
        val result = StationRetryResponse(sourceIds.size, receipts.size - sourceIds.size)
        audits.enqueue(BusinessAuditEvent(
            principal.userId,
            "STATION_INGEST_RETRY",
            "BUSINESS",
            "SUCCESS",
            "STATION_INGEST",
            null,
            UUID.randomUUID().toString(),
            null,
            mapOf("queued" to result.queued, "skipped" to result.skipped)
        ))
        return result
    }

    /** 严格解析 16 字节 URL-safe Base64 回执，非法输入不得访问数据库。 */
    private fun decodeReceipt(value: String): ByteArray {
        if (!value.matches(Regex("^[A-Za-z0-9_-]{22}$"))) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_RECEIPT_INVALID")
        }
        return runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "STATION_RECEIPT_INVALID", cause = it) }
            .also {
                if (it.size != 16) {
                    throw ClientException(ErrorCode.BAD_REQUEST, "STATION_RECEIPT_INVALID")
                }
            }
    }
}
