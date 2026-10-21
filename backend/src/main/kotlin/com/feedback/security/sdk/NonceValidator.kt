package com.feedback.security.sdk

import com.feedback.service.sdk.*

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import com.feedback.config.RuntimeConfigService

/**
 * Nonce 去重校验 — Redis TTL 5分钟防重放
 */
@Service
class NonceValidator(
    private val redisTemplate: StringRedisTemplate, private val runtimeConfig: RuntimeConfigService? = null
) {
    companion object {
        private const val NONCE_PREFIX = "sdk:nonce:"
    }

    /**
     * 检查 nonce 是否已使用。首次使用返回 true（通过校验），
     * 同时写入 Redis 并设置 5 分钟 TTL。
     */
    fun validateAndMark(nonce: String): Boolean {
        val key = "$NONCE_PREFIX$nonce"
        val success = redisTemplate.opsForValue()
            .setIfAbsent(key, "1", Duration.ofMinutes((runtimeConfig?.int("sdk.nonce-expire-minutes",5) ?: 5).toLong()))
        return success ?: false
    }
}
