package com.feedback.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisWebSessionStoreTest {
    private val redis = mockk<StringRedisTemplate>()
    private val values = mockk<ValueOperations<String, String>>()
    private val store = RedisWebSessionStore(redis, 8)

    @Test
    fun `creates a random opaque id while Redis stores only its digest`() {
        val key = slot<String>()
        every { redis.opsForValue() } returns values
        every { values.set(capture(key), "7:4", Duration.ofHours(8)) } returns Unit
        val principal = principal()
        val id = store.create(principal.userId, principal.authVersion)
        assertEquals(43, id.length)
        assertFalse(key.captured.contains(id))
        assertEquals(84, key.captured.length)
        verify { values.set(any(), "7:4", Duration.ofHours(8)) }
    }

    @Test
    fun `resolves only well-formed server-side session state`() {
        every { redis.opsForValue() } returns values
        every { values.get(any()) } returns "7:4"
        assertEquals(StoredWebSession(7, 4), store.resolve(SESSION_ID))
        assertNull(store.resolve("not-a-valid-session"))
    }

    @Test
    fun `revocation deletes the digest key and never the browser credential`() {
        val key = slot<String>()
        every { redis.delete(capture(key)) } returns true
        store.revoke(SESSION_ID)
        assertFalse(key.captured.contains(SESSION_ID))
    }

    private fun principal() = WebPrincipal(
        7, "auditor", "unused", "Auditor", setOf("AUDITOR"), emptySet(), authVersion = 4
    )

    private companion object {
        const val SESSION_ID = "abcdefghijklmnopqrstuvwxyzABCDEFGH012345678"
    }
}
