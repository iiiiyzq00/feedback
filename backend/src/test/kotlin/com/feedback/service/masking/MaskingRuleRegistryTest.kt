package com.feedback.service.masking

import com.feedback.common.exception.ClientException
import com.feedback.repository.MaskingAuditRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import com.feedback.repository.masking.MaskingConfigRepository

class MaskingRuleRegistryTest {
    private val configs=mockk<MaskingConfigRepository>().also { every { it.active(any()) } returns null }
    private val registry=MaskingRuleRegistry(configs,jacksonObjectMapper(),mockk<MaskingAuditRepository>(),mockk())
    @Test fun `management catalog exposes every versionable runtime rule`() {
        val rules=registry.list()
        assertEquals(14,rules.size)
        assertEquals(MaskingVersionService.RULES,rules.map{it.name}.toSet())
    }
    @Test fun `high risk rules cannot be disabled from management API`() {
        assertThrows(ClientException::class.java){registry.update(1,MaskingRuleUpdate(false))}
        assertThrows(ClientException::class.java){registry.update(4,MaskingRuleUpdate(false))}
    }
}
