package com.feedback.security.sdk

import com.feedback.service.sdk.*

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import com.feedback.config.RuntimeConfigService

/**
 * 按 app_id / IP 限流
 */
@Service
class RateLimiter(
    private val redisTemplate: StringRedisTemplate, private val runtimeConfig: RuntimeConfigService? = null
) {
    companion object {
        private const val APP_RATE_PREFIX = "sdk:rate:app:"
        private const val IP_RATE_PREFIX = "sdk:rate:ip:"
        const val WINDOW_SECONDS = 60L
    }

    /**
     * 检查是否超出频率限制
     * @return true 如果未超限
     */
    fun allow(appId: String, clientIp: String): Boolean {
        if (!checkLimit("$APP_RATE_PREFIX$appId", (runtimeConfig?.int("sdk.rate-limit",100) ?: 100).toLong())) {
            return false
        }
        if (!checkLimit("$IP_RATE_PREFIX$clientIp", (runtimeConfig?.int("sdk.ip-rate-limit",60) ?: 60).toLong())) {
            return false
        }
        return true
    }

    /** 使用 Redis 原子计数执行限流；缓存异常时按安全策略失败关闭而非绕过限制。 */
    private fun checkLimit(key: String, limit: Long): Boolean {
        val count = redisTemplate.opsForValue().increment(key) ?: 1
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS))
        }
        return count <= limit
    }
}
