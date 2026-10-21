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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdkIngestHealthContractTest {
    @Test
    fun `publishes six result types windows reconciliation and runtime limits`() {
        val config=mockk<RuntimeConfigService>()
        every { config.int("sdk.rate-limit",100) } returns 120
        every { config.int("sdk.ip-rate-limit",60) } returns 70
        val contract=SdkIngestHealthContract(config).describe()
        assertEquals("sdk-transport-v2",contract["version"])
        assertEquals(6,(contract["resultTypes"] as List<*>).size)
        assertEquals(4,(contract["windows"] as List<*>).size)
        assertEquals(300,contract["currentWindowSeconds"])
        assertEquals(60,contract["rateLimitWindowSeconds"])
        assertEquals(120,contract["appLimitPerMinute"])
        assertEquals(70,contract["ipLimitPerMinute"])
        assertTrue(contract["reconciliationFormula"].toString().contains("otherErrorCount"))
    }
}
