package com.feedback.sdk.config

import java.net.URI

/**
 * SDK 配置 — 宿主 App 初始化时传入
 *
 * 安全说明：
 * - appKey:    公开的应用标识（随请求明文传输 → X-App-Key 头）
 * - appSecret: 共享密钥（仅用于 HMAC 签名计算，不传输）
 * - serverPublicKey: RSA-4096 公钥（用于封装 AES dataKey）
 *
 * 三个值由服务端管理员分发，支持独立轮换。
 */
data class SDKConfig(
    val appId: String,                   // 应用标识（一般与 packageName 一致）
    val appKey: String,                  // 公开应用标识 — X-App-Key 头
    val serverUrl: String,               // 服务端地址
    val appSecret: String,               // HMAC 签名密钥 — 不传输，且不得与 appKey 相同
    val serverPublicKeyPem: String? = null, // RSA 公钥 — 封装 dataKey（null 则用内置公钥）
    val serverTransportKeyId: String = "sdk-transport-v2",
    val connectTimeoutMs: Long = 10_000L,
    val readTimeoutMs: Long = 30_000L,
    val maxRetries: Int = 8,
    val bodyRetentionDays: Int = 30,
    val attachmentRetentionDays: Int = 7,
    val attachmentMaxSizeMb: Int = 200,
    /** 仅供隔离测试网真机联调；生产宿主不得开启。 */
    val allowInsecurePrivateNetworkForTesting: Boolean = false
) {
    init {
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(appKey.isNotBlank()) { "appKey must not be blank" }
        require(appSecret.length >= 16) { "appSecret must contain at least 16 characters" }
        require(appSecret != appKey) { "appSecret must not equal the public appKey" }
        val uri = runCatching { URI(serverUrl) }.getOrNull()
        val emulatorLoopback = uri?.scheme == "http" && uri.host == "10.0.2.2"
        val privateNetworkTest = allowInsecurePrivateNetworkForTesting &&
            uri?.scheme == "http" && uri.host?.let { it == "127.0.0.1" || it == "localhost" || isPrivateIpv4(it) } == true
        require(uri?.scheme == "https" || emulatorLoopback || privateNetworkTest) {
            "serverUrl must use HTTPS (emulator loopback or an explicitly enabled private test network is allowed)"
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
