package com.feedback.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

/** Station 双写外部端口配置；集中校验 HTTPS 地址、凭据和启用前置条件。 */
@Component
@ConfigurationProperties(prefix = "app.station-feedback.dual-write")
class StationDualWriteProperties {
    var enabled: Boolean = false
    var storageSecret: String = ""
    var storageKeyId: String = "station-dual-write-v1"
    var oldFbpTokenUrl: String = ""
    var oldFbpSubmitBaseUrl: String = ""
    var oldFbpAppId: String = ""
    var oldFbpSecret: String = ""
    var tokenCacheSeconds: Long = 300
    var connectTimeoutSeconds: Long = 2
    var callTimeoutSeconds: Long = 8
    var workerDelayMs: Long = 1_000
    var allowHttpForLoopback: Boolean = false

/** 返回启用 Station 双写前的全部安全配置缺口，内容只包含稳定错误码。 */
    fun readinessErrors(): List<String> = buildList {
        if (!enabled) add("STATION_DUAL_WRITE_DISABLED")
        if (storageSecret.length < 32) add("STATION_DUAL_WRITE_STORAGE_SECRET_INVALID")
        if (storageKeyId.isBlank() || storageKeyId.length > 50) add("STATION_DUAL_WRITE_KEY_ID_INVALID")
        validateHttps(oldFbpTokenUrl, "STATION_OLD_FBP_TOKEN_URL_INVALID")
        validateHttps(oldFbpSubmitBaseUrl, "STATION_OLD_FBP_SUBMIT_URL_INVALID")
        if (oldFbpAppId.isBlank()) add("STATION_OLD_FBP_APP_ID_MISSING")
        if (oldFbpSecret.isBlank()) add("STATION_OLD_FBP_SECRET_MISSING")
        if (tokenCacheSeconds !in 1..3600) add("STATION_OLD_FBP_TOKEN_CACHE_INVALID")
        if (connectTimeoutSeconds !in 1..30 || callTimeoutSeconds !in connectTimeoutSeconds..120) add("STATION_OLD_FBP_TIMEOUT_INVALID")
    }

/** 判断 Station 双写配置是否满足全部启用条件，不执行网络或数据库访问。 */
    fun ready() = readinessErrors().isEmpty()

/** 启动时记录双写就绪状态；日志不输出应用密钥、令牌或完整端点凭据。 */
    @PostConstruct
    fun logReadiness() {
        logger.info("Station reliable dual write configuration: enabled={}, ready={}, errors={}",
            enabled, ready(), readinessErrors())
    }

/** 校验外部端点必须使用 HTTPS 且包含有效主机名，失败时追加稳定配置错误码。 */
    private fun MutableList<String>.validateHttps(value: String, code: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        val loopback = uri?.host in setOf("localhost", "127.0.0.1", "::1")
        val validScheme = uri?.scheme == "https" || (allowHttpForLoopback && loopback && uri?.scheme == "http")
        if (uri == null || !validScheme || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) add(code)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(StationDualWriteProperties::class.java)
    }
}
