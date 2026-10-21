package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.service.station.StationFeedbackOperationsService
import org.springframework.stereotype.Service
import java.util.Base64

@Service
/** Station 运维查询应用服务；执行授权后的有界分页与聚合，不向入口传播 PO 或 Mapper。 */
class StationFeedbackOperationsServiceImpl(
    private val channels: StationOnlineChannelRepository
) : StationFeedbackOperationsService {
    /** 查询有界 Station 运维任务页，状态字符串只在当前 HTTP 边界规范化。 */
    override fun tasks(status: String?, page: Int, size: Int): Map<String, Any> {
        if (page < 1 || size !in 1..100) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_OPERATIONS_PAGE_INVALID")
        }
        val normalized = status?.takeIf(String::isNotBlank)?.uppercase()
        if (normalized != null && normalized !in STATUSES) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_OPERATIONS_STATUS_INVALID")
        }
        val (rows, total) = channels.operations(normalized, page, size)
        val records = rows.map { row -> linkedMapOf<String, Any?>(
            "sourceId" to row.sourceId,"receiptId" to normalizeReceipt(row.receiptId),"apiVersion" to row.apiVersion,
            "productId" to row.productId,"appId" to row.appId,"platform" to row.platform,"subChannel" to row.subChannel,
            "status" to row.status,"processingStage" to row.processingStage,"contactStatus" to row.contactStatus,
            "attributeStatus" to row.attributeStatus,"retryCount" to row.retryCount,"maxRetryCount" to row.maxRetryCount,
            "nextRetryAt" to row.nextRetryAt,"errorCode" to row.errorCode,"errorSummary" to row.errorSummary,
            "receivedAt" to row.receivedAt,"processedAt" to row.processedAt,"retryable" to row.retryable) }
        val summary = channels.operationsSummary()
        return mapOf("records" to records, "total" to total, "page" to page, "size" to size, "summary" to summary,
            "channel" to "STATION_FEEDBACK", "channelBoundary" to "旧反馈平台兼容渠道；不属于通用反馈SDK渠道")
    }

    /** 将数据库 UUID 字节保持为既有 URL-safe Base64 回执格式。 */
    private fun normalizeReceipt(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private val STATUSES = setOf("RECEIVED", "PROCESSING", "RETRY_PENDING", "REVIEW_NEEDED", "COMPLETED", "FAILED_FINAL")
    }
}
