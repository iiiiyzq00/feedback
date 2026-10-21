package com.feedback.manager.sdk

import com.feedback.service.sdk.*

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.sdk.SdkRequestMetricPo
import com.feedback.repository.sdk.SdkTelemetryRepository

/** SDK 请求遥测记录器；只保存裁剪后的分类、耗时和安全错误码。 */
@Service
class SdkRequestTelemetryRecorder(
    private val repository: SdkTelemetryRepository,
    private val objectMapper: ObjectMapper
) {
    private val log=LoggerFactory.getLogger(javaClass)

    /** 记录单次 SDK 请求遥测；裁剪路径和错误分类，不保存签名、nonce、密文或正文。 */
    fun record(appId:String?,status:Int,errorCode:String?,durationMs:Long){
        val result=classify(status)
        val normalizedAppId = appId?.take(50)?.ifBlank { null } ?: "UNKNOWN"
        val normalizedStatus = status.coerceIn(100, 599)
        val normalizedDuration = durationMs.coerceIn(0, Int.MAX_VALUE.toLong())
        val normalizedError = errorCode?.take(80)
        runCatching { repository.insertRequestMetric(SdkRequestMetricPo(
            appId = normalizedAppId,
            httpStatus = normalizedStatus,
            durationMs = normalizedDuration,
            errorCode = normalizedError,
            resultType = result,
            snapshotJson = objectMapper.writeValueAsString(SdkRequestMetricSnapshot(
                normalizedAppId, normalizedStatus, result, normalizedError, normalizedDuration
            ))
        )) }
            .onFailure { log.error("SDK request telemetry write failed",it) }
    }

    /** 把内部异常映射为封闭的遥测结果类别，未知异常统一归为安全服务失败。 */
    fun classify(status:Int):String=when {
            status in 200..299 -> "SUCCESS"
            status==429 -> "RATE_LIMITED"
            status==401 || status==403 -> "AUTH_FAILED"
            status==400 || status==422 -> "BAD_REQUEST"
            status>=500 -> "SERVER_ERROR"
            else -> "OTHER_ERROR"
        }

    /** 按保留期和批量上限清理传输遥测；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun purgeExpired():Int=repository.purgeExpiredRequestMetrics(10_000)
}

/** 事务外准备的 SDK 请求指标快照；字段已裁剪，可直接交给短事务写入。 */
private data class SdkRequestMetricSnapshot(
    val appId: String,
    val httpStatus: Int,
    val resultType: String,
    val errorCode: String?,
    val durationMs: Long
)
