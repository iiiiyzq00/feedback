package com.feedback.service.impl.sdk

import com.feedback.manager.sdk.SdkTelemetryCommitWorker
import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.dto.sdk.SdkTelemetryRequest
import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import com.feedback.repository.sdk.SdkTelemetryRepository
import com.feedback.service.sdk.SdkTelemetryService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 持久化 SDK 传输诊断事件。
 *
 * 一次请求最多 100 条，由 MyBatis 合并为一次多值写入以减少数据库往返；事务仅包围数据库写入。
 */
@Service
class SdkTelemetryServiceImpl(
    private val telemetry: SdkTelemetryRepository,
    private val objectMapper: ObjectMapper,
    private val commitWorker: SdkTelemetryCommitWorker
) : SdkTelemetryService {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 接收有界 SDK 遥测批次；事务外完成裁剪与 JSON 组装，再交给短事务批量写入器。 */
    override fun ingestTelemetry(request: SdkTelemetryRequest, appKey: String, clientIp: String) {
        if (request.events.isEmpty() || request.events.size > MAX_EVENTS_PER_REQUEST) {
            throw ClientException(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_TELEMETRY_EVENT_COUNT_INVALID")
        }

        val batch = request.events.map { event ->
            val detail = objectMapper.createObjectNode().apply {
                put("app_key", appKey)
                put("failure_stage", event.failureStage.take(30))
                put("error_type", event.errorType.take(50))
                put("network_type", event.networkType?.take(20))
                put("retry_count", event.retryCount)
                put("attachment_size_bytes", event.attachmentSizeBytes)
                put("sdk_version", event.sdkVersion?.take(30))
                put("app_version", event.appVersion?.take(50))
                put("duration_ms", event.durationMs)
            }
            SdkTransportTelemetryPo(
                targetId = event.clientFeedbackId?.take(100),
                detailJson = objectMapper.writeValueAsString(detail),
                ipAddress = clientIp.take(50)
            )
        }

        try {
            commitWorker.commit(batch)
        } catch (error: IllegalStateException) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_TELEMETRY_BATCH_INSERT_FAILED", cause = error)
        }
        log.info("SDK telemetry persisted: events={}", batch.size)
    }

    private companion object {
        const val MAX_EVENTS_PER_REQUEST = 100
    }
}
