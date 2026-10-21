package com.feedback.manager.sdk

import com.feedback.service.sdk.*

import com.feedback.config.RuntimeConfigService
import org.springframework.stereotype.Service

/** SDK 接入健康契约提供器；只暴露公开能力与限制，不返回密钥或内部拓扑。 */
@Service
class SdkIngestHealthContract(private val runtimeConfig: RuntimeConfigService) {
    /** 生成 SDK 接入健康契约描述，只返回公开能力和限制，不暴露密钥或内部拓扑。 */
    fun describe(): Map<String, Any> = mapOf(
        "version" to VERSION,
        "resultTypes" to RESULT_TYPES,
        "windows" to WINDOWS,
        "currentWindowSeconds" to CURRENT_WINDOW_SECONDS,
        "rateLimitWindowSeconds" to RATE_LIMIT_WINDOW_SECONDS,
        "appLimitPerMinute" to runtimeConfig.int("sdk.rate-limit", 100),
        "ipLimitPerMinute" to runtimeConfig.int("sdk.ip-rate-limit", 60),
        "rateSemantics" to mapOf("average" to "窗口内全部入口请求数 ÷ 窗口秒数", "current" to "最近 300 秒入口请求数 ÷ 300", "peak" to "所选窗口各时间分箱入口请求数 ÷ 分箱秒数的最大值"),
        "reconciliationFormula" to "transportFailureCount = rateLimitedCount + authFailureCount + badRequestCount + serverErrorCount + otherErrorCount"
    )

    companion object {
        const val VERSION = "sdk-transport-v2"
        const val CURRENT_WINDOW_SECONDS = 300
        const val RATE_LIMIT_WINDOW_SECONDS = 60
        val RESULT_TYPES = listOf(
            mapOf("code" to "SUCCESS", "httpStatus" to "2xx", "label" to "接收成功"),
            mapOf("code" to "RATE_LIMITED", "httpStatus" to "429", "label" to "触发限流"),
            mapOf("code" to "AUTH_FAILED", "httpStatus" to "401/403", "label" to "鉴权失败"),
            mapOf("code" to "BAD_REQUEST", "httpStatus" to "400/422", "label" to "参数错误"),
            mapOf("code" to "SERVER_ERROR", "httpStatus" to "5xx", "label" to "服务异常"),
            mapOf("code" to "OTHER_ERROR", "httpStatus" to "其余非 2xx", "label" to "其他入口异常")
        )
        val WINDOWS = listOf(
            mapOf("range" to "1h", "windowSeconds" to 3600, "bucketSeconds" to 60, "bucketLabel" to "每分钟"),
            mapOf("range" to "6h", "windowSeconds" to 21600, "bucketSeconds" to 3600, "bucketLabel" to "每小时"),
            mapOf("range" to "24h", "windowSeconds" to 86400, "bucketSeconds" to 3600, "bucketLabel" to "每小时"),
            mapOf("range" to "7d", "windowSeconds" to 604800, "bucketSeconds" to 86400, "bucketLabel" to "每天")
        )
    }
}
