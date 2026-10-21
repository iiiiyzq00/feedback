package com.feedback.rpc.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.rpc.api.FeedbackPlatformRpcApi
import com.feedback.rpc.api.FeedbackRpcRequest
import com.feedback.rpc.api.FeedbackRpcResponse
import com.feedback.security.station.StationGatewayPrincipal
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import com.feedback.config.StationDualWriteProperties
import org.apache.dubbo.config.annotation.DubboService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** Station RPC 适配器；保持旧协议编码并把请求委托给 Station Service 契约。 */
@DubboService(interfaceClass = FeedbackPlatformRpcApi::class, version = "1.0.0")
@ConditionalOnProperty(prefix = "dubbo", name = ["enabled"], havingValue = "true")
class FeedbackPlatformRpcApiImpl(
    private val ingestService: StationFeedbackIngestService,
    private val objectMapper: ObjectMapper,
    private val dualWriteTasks: StationDualWriteTaskService,
    private val dualWriteProperties: StationDualWriteProperties,
    meterRegistry: MeterRegistry
) : FeedbackPlatformRpcApi {
    private val successCounter = Counter.builder("feedback.station.rpc.requests")
        .tag("outcome", "success").register(meterRegistry)
    private val failureCounter = Counter.builder("feedback.station.rpc.requests")
        .tag("outcome", "failure").register(meterRegistry)

    /** 提交 Station RPC/旧平台请求；保持外部协议兼容，并把远端失败归一为安全错误码。 */
    override fun submit(request: FeedbackRpcRequest?): FeedbackRpcResponse {
        if (request == null) {
            failureCounter.increment()
            return failed()
        }
        return try {
            validate(request)
            val version = StationApiVersion.valueOf(request.apiVersion)
            val stationRequest = StationFeedbackRequest(
                    productId = request.productId,
                    appVersion = request.appVersion,
                    title = request.title,
                    content = request.content,
                    phone = request.phone,
                    platform = request.platform,
                    type = request.type,
                    channel = request.channel,
                    photoUrlList = request.photoUrlList,
                    feedbackAttributeRecords = request.feedbackAttributeRecordsJson
                        .takeIf(String::isNotBlank)?.let(objectMapper::readTree),
                    userId = request.userId,
                    description = request.description,
                    appId = request.appId,
                    appName = request.appName
                )
            val principal = StationGatewayPrincipal(
                    clientId = "station-feedback-rpc",
                    userId = request.resolvedUserId,
                    productCode = request.productCode,
                    adapter = true
                )
            val result = if (dualWriteProperties.enabled) {
                val accepted = dualWriteTasks.accept(
                    StationDualWriteEnvelope(stationRequest, version, request.idempotencyKey, principal)
                )
                RpcAcceptResult(accepted.receiptId)
            } else {
                RpcAcceptResult(ingestService.accept(stationRequest, version, request.idempotencyKey, principal).receiptId)
            }
            successCounter.increment()
            FeedbackRpcResponse("0", "ok", result.receiptId)
        } catch (e: Exception) {
            failureCounter.increment()
            logger.warn("Station feedback RPC request rejected: errorType={}", e.javaClass.simpleName)
            failed()
        }
    }

    /** 校验输入或不变量：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun validate(request: FeedbackRpcRequest) {
        if (request.apiVersion != "V1" && request.apiVersion != "V2") {
            invalid("STATION_RPC_API_VERSION_INVALID")
        }
        if (!request.idempotencyKey.matches(Regex("^[A-Za-z0-9._:-]{8,100}$"))) {
            invalid("STATION_RPC_IDEMPOTENCY_KEY_INVALID")
        }
        if (request.productCode.isBlank() || request.productCode.length > 100) {
            invalid("STATION_RPC_PRODUCT_CODE_INVALID")
        }
        if (request.resolvedUserId.isBlank() || request.resolvedUserId.length > 100) {
            invalid("STATION_RPC_USER_ID_INVALID")
        }
        if (request.productId.isBlank() || request.productId.length > 100) {
            invalid("STATION_RPC_PRODUCT_ID_INVALID")
        }
        if (request.title.isBlank() || request.title.length > 500) {
            invalid("STATION_RPC_TITLE_INVALID")
        }
        if (request.content.isBlank() || request.content.length > 100_000) {
            invalid("STATION_RPC_CONTENT_INVALID")
        }
        if (request.platform.isBlank() || request.platform.length > 50) {
            invalid("STATION_RPC_PLATFORM_INVALID")
        }
        if (request.photoUrlList.size > 9 || request.photoUrlList.any { it.length > 2048 }) {
            invalid("STATION_RPC_PHOTO_URLS_INVALID")
        }
    }

    /** 构造 Station RPC 参数错误，使用稳定错误码且不回显不可信输入。 */
    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

    /** 生成兼容旧 RPC 协议的失败响应，不向调用方暴露内部异常详情。 */
    private fun failed() = FeedbackRpcResponse("1", "failed", "")

/** Station RPC 内部受理结果；只保留可返回的稳定回执。 */
    private data class RpcAcceptResult(val receiptId: String)

    companion object {
        private val logger = LoggerFactory.getLogger(FeedbackPlatformRpcApiImpl::class.java)
    }
}
