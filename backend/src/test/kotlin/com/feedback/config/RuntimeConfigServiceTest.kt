package com.feedback.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.config.SystemConfigRecord
import com.feedback.repository.config.SystemConfigRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RuntimeConfigServiceTest {
    private val repository = mockk<SystemConfigRepository>()
    private val service = RuntimeConfigService(repository, jacksonObjectMapper())

    @Test
    fun `uses explicit defaults only for missing or malformed historical values`() {
        every { repository.activeRuntime("missing") } returns null
        every { repository.activeRuntime("broken") } returns record("broken", "not-json")

        assertEquals(7, service.int("missing", 7))
        assertEquals(BigDecimal("0.25"), service.decimal("broken", BigDecimal("0.25")))
    }

    @Test
    fun `database failures propagate instead of silently weakening runtime controls`() {
        every { repository.activeRuntime("sdk.rate-limit") } throws IllegalStateException("db unavailable")

        assertThrows(IllegalStateException::class.java) { service.int("sdk.rate-limit", 100) }
    }

    @Test
    fun `returns a typed dictionary without exposing a generic map contract`() {
        every { repository.activeRuntime("cluster.product-scope-map") } returns record(
            "cluster.product-scope-map",
            "{\"SDK:app-a\":\"LEARNING_MACHINE\",\"enabled\":\"true\"}"
        )

        val dictionary = service.dictionary("cluster.product-scope-map")

        assertEquals("LEARNING_MACHINE", dictionary["SDK:app-a"])
        assertEquals(setOf("SDK:app-a", "enabled"), dictionary.keys())
        assertEquals(setOf("enabled"), dictionary.enabledKeys())
    }

    private fun record(key: String, value: String) = SystemConfigRecord(
        id = 1,
        configKey = key,
        configVersion = "v1",
        configValue = value
    )
}
