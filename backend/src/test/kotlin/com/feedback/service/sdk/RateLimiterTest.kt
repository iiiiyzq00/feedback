package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.config.RuntimeConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RateLimiterTest {
    private val redis=mockk<StringRedisTemplate>()
    private val values=mockk<ValueOperations<String,String>>()
    private val config=mockk<RuntimeConfigService>()

    @Test
    fun `uses independent runtime app and ip limits with sixty second windows`() {
        every { redis.opsForValue() } returns values
        every { config.int("sdk.rate-limit",100) } returns 2
        every { config.int("sdk.ip-rate-limit",60) } returns 1
        every { values.increment("sdk:rate:app:app-a") } returns 1
        every { values.increment("sdk:rate:ip:192.0.2.1") } returns 2
        every { redis.expire(any(),any<Duration>()) } returns true

        assertFalse(RateLimiter(redis,config).allow("app-a","192.0.2.1"))
        verify { config.int("sdk.rate-limit",100); config.int("sdk.ip-rate-limit",60) }
        verify { redis.expire("sdk:rate:app:app-a",Duration.ofSeconds(60)) }
    }

    @Test
    fun `allows request below both configured limits`() {
        every { redis.opsForValue() } returns values
        every { config.int(any(),any()) } answers { secondArg() }
        every { values.increment(any()) } returns 2
        assertTrue(RateLimiter(redis,config).allow("app-b","192.0.2.2"))
    }
}
