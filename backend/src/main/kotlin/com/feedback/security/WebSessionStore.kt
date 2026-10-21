package com.feedback.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/** Redis 会话中仅保存定位用户和废止旧会话所需的最小字段。 */
data class StoredWebSession(val userId: Long, val authVersion: Long)

/** 不透明 Web 会话的创建、解析与撤销端口。 */
interface WebSessionStore {
    /** 新会话 Cookie 应使用的有效期秒数。 */
    val ttlSeconds: Long
    /** 创建服务端会话并返回只交给浏览器的随机标识。 */
    fun create(userId: Long, authVersion: Long): String
    /** 解析格式合法且仍在有效期内的会话。 */
    fun resolve(sessionId: String): StoredWebSession?
    /** 撤销格式合法的会话；不存在时保持幂等。 */
    fun revoke(sessionId: String)
}

/**
 * 由 Redis 支撑的浏览器不透明会话。
 *
 * 浏览器只拿到一个随机 256 位标识符，Redis 键里存的是它的
 * SHA-256 摘要，因此即使列出 Redis 键也无法反推出浏览器凭据。
 * Redis 报错时刻意向上抛出：认证必须失败关闭，而不是回退到
 * 无状态凭据。
 */
@Service
class RedisWebSessionStore(
    private val redis: StringRedisTemplate,
    @Value("\${app.auth.session.ttl-hours:8}") ttlHours: Long
) : WebSessionStore {
    private val random = SecureRandom()
    private val ttl = Duration.ofHours(ttlHours.coerceIn(1, 168))
    override val ttlSeconds: Long get() = ttl.seconds

    /** 生成 256 位随机标识，Redis 仅以其摘要为 key 保存用户和认证版本。 */
    override fun create(userId: Long, authVersion: Long): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        redis.opsForValue().set(key(sessionId), "$userId:$authVersion", ttl)
        return sessionId
    }

    /** 在访问 Redis 前先验证固定长度 URL-safe 格式，非法输入直接视为未认证。 */
    override fun resolve(sessionId: String): StoredWebSession? {
        if (!SESSION_ID.matches(sessionId)) {
            return null
        }
        val fields = redis.opsForValue().get(key(sessionId))?.split(':', limit = 2) ?: return null
        if (fields.size != 2) {
            return null
        }
        return StoredWebSession(fields[0].toLongOrNull() ?: return null, fields[1].toLongOrNull() ?: return null)
    }

    /** 删除会话摘要 key，原始会话标识永不进入 Redis key 空间。 */
    override fun revoke(sessionId: String) {
        if (SESSION_ID.matches(sessionId)) {
            redis.delete(key(sessionId))
        }
    }

    /** 将浏览器凭据转换为不可直接重放的 Redis key。 */
    private fun key(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sessionId.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        return "auth:web-session:v1:$digest"
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9_-]{43}")
    }
}
