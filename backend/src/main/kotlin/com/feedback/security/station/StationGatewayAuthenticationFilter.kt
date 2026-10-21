package com.feedback.security.station

import com.fasterxml.jackson.annotation.JsonIgnore
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.security.SecurityErrorWriter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import com.feedback.model.auth.PlatformPermissions
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Station 网关认证后的受控主体；仅携带授权所需用户、产品和适配器标记。 */
data class StationGatewayPrincipal(
    val clientId: String,
    val userId: String,
    val productCode: String,
    val adapter: Boolean = false
) : java.security.Principal {
    @JsonIgnore
    /** Java Principal 名称使用网关解析后的业务用户 ID。 */
    override fun getName() = userId
}

/**
 * 站点反馈网关 HMAC 认证过滤器。
 * 校验请求大小、时间窗、签名和 Redis nonce；适配器模式额外验证允许的客户端及上游解析用户。
 */
@Component
class StationGatewayAuthenticationFilter(
    private val redis: StringRedisTemplate,
    private val errors: SecurityErrorWriter,
    private val ucAuthenticator: StationUcAuthenticator,
    @Value("\${app.station-feedback.gateway-auth.enabled:false}") private val enabled: Boolean,
    @Value("\${app.station-feedback.gateway-auth.secret:}") private val secret: String,
    @Value("\${app.station-feedback.gateway-auth.max-body-bytes:65536}") private val maxBodyBytes: Int,
    @Value("\${app.station-feedback.gateway-auth.window-seconds:300}") private val windowSeconds: Long,
    @Value("\${app.station-feedback.gateway-auth.adapter-enabled:false}") private val adapterEnabled: Boolean,
    @Value("\${app.station-feedback.gateway-auth.adapter-client-ids:}") adapterClientIdsRaw: String,
    @Value("\${app.station-feedback.gateway-auth.adapter-resolved-user-id-header:X-Station-Resolved-User-Id}") private val adapterResolvedUserIdHeader: String
) : OncePerRequestFilter() {
    private val adapterClientIds = adapterClientIdsRaw.split(',')
        .map(String::trim)
        .filter(String::isNotBlank)
        .toSet()

    init {
        if (adapterEnabled && adapterClientIds.isEmpty()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ADAPTER_CLIENT_IDS_REQUIRED")
        }
    }

    /** 仅拦截两个站点反馈 POST 入口，其余请求交给 Web 安全链。 */
    override fun shouldNotFilter(request: HttpServletRequest) =
        request.method != "POST" || (request.requestURI != "/v1/feedback" && request.requestURI != "/v1/feedback/new")

    /**
     * 在读取缓存请求体后校验 HMAC 和一次性 nonce；任一依赖或输入异常均失败关闭，验证成功才写安全上下文。
     */
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!enabled || secret.length < 32) {
            errors.unauthorized(response)
            return
        }
        val body = request.inputStream.readNBytes(maxBodyBytes.coerceIn(1024, 1_048_576) + 1)
        if (body.size > maxBodyBytes) {
            errors.unauthorized(response)
            return
        }
        val timestamp = request.getHeader("X-Station-Timestamp")?.toLongOrNull()
        val nonce = request.getHeader("X-Station-Nonce")
        val identity = request.getHeader("X-Station-Client-Id")
        val ucToken = request.getHeader("x-auth-token")
        val productCode = request.getHeader("x-app-code")
        val clientVersion = request.getHeader("x-app-version")
        val signature = request.getHeader("X-Station-Signature")
        val adapterResolvedUserId = request.getHeader(adapterResolvedUserIdHeader)
        if (timestamp == null || nonce == null || identity == null || (!adapterEnabled && ucToken.isNullOrBlank()) ||
            productCode.isNullOrBlank() || clientVersion == null || signature == null ||
            nonce.length !in 16..128 || identity.length !in 1..100 || productCode.length > 100 ||
            !clientVersion.matches(Regex("^\\d+\\.\\d+\\.\\d+$")) ||
            kotlin.math.abs(Instant.now().epochSecond - timestamp) > windowSeconds.coerceIn(30, 600)) {
            errors.unauthorized(response)
            return
        }
        if (adapterEnabled) {
            if (identity !in adapterClientIds) {
                errors.unauthorized(response)
                return
            }
            if (adapterResolvedUserId.isNullOrBlank() || adapterResolvedUserId.length > 100) {
                errors.unauthorized(response)
                return
            }
        }
        val canonicalParts = mutableListOf(request.method, request.requestURI, timestamp.toString(), nonce, identity, sha256Hex(body))
        if (adapterEnabled) {
            canonicalParts += adapterResolvedUserId ?: run {
                errors.unauthorized(response)
                return
            }
        }
        val canonical = canonicalParts.joinToString("\n")
        val expected = hmacHex(secret, canonical)
        if (!signature.matches(Regex("^[0-9a-fA-F]{64}$")) || !MessageDigest.isEqual(expected.toByteArray(), signature.lowercase().toByteArray())) {
            errors.unauthorized(response)
            return
        }
        val nonceKey = "station:gateway:nonce:${sha256Hex("$identity:$nonce".toByteArray())}"
        val accepted = runCatching { redis.opsForValue().setIfAbsent(nonceKey, "1", Duration.ofSeconds(windowSeconds)) == true }.getOrDefault(false)
        if (!accepted) {
            errors.unauthorized(response)
            return
        }
        val userId = if (adapterEnabled) {
            (adapterResolvedUserId ?: run {
                errors.unauthorized(response)
                return
            }).take(100)
        } else {
            val token = ucToken ?: run {
                errors.unauthorized(response)
                return
            }
            runCatching { ucAuthenticator.authenticate(token, productCode, request.remoteAddr.orEmpty()) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.take(100)
        }
        if (userId == null) {
            errors.unauthorized(response)
            return
        }
        val principal = StationGatewayPrincipal(identity, userId, productCode, adapterEnabled)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, listOf(SimpleGrantedAuthority(PlatformPermissions.STATION_INGEST.code)))
        request.setAttribute("STATION_GATEWAY_AUTHENTICATED", true)
        chain.doFilter(CachedBodyRequest(request, body), response)
    }

    /** 生成请求体或 nonce 组成项的稳定 SHA-256 十六进制摘要。 */
    private fun sha256Hex(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }

    /** 使用共享密钥计算规范请求串 HMAC，不记录密钥或明文 token。 */
    private fun hmacHex(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 缓存已参与签名校验的请求体，保证下游只能读取同一字节序列。 */
private class CachedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
    /** 为下游创建只读缓存流，不再次读取网络输入。 */
    override fun getInputStream(): ServletInputStream {
        val input = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            /** 逐字节读取缓存体。 */
            override fun read() = input.read()
            /** 仅在缓存字节全部读取后报告结束。 */
            override fun isFinished() = input.available() == 0
            /** 内存流始终可同步读取。 */
            override fun isReady() = true
            /** 同步内存流不使用异步 ReadListener。 */
            override fun setReadListener(listener: ReadListener?) = Unit
        }
    }
}
