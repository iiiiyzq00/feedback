package com.feedback.service.masking

import com.feedback.repository.MaskingAuditRepository
import com.feedback.repository.masking.OutboundPrivacyRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundPrivacyGateTest {
    private val auditRepository = mockk<MaskingAuditRepository>(relaxed = true).also {
        every { it.save(any()) } answers { firstArg() }
    }
    private val repository = mockk<OutboundPrivacyRepository>(relaxed = true)
    private val mapper = jacksonObjectMapper()
    private val gate = OutboundPrivacyGate(MaskingPipeline(auditRepository, mapper, NoopNerMasker), repository, mapper)

    @Test
    fun `already safe masked text can leave the platform`() {
        assertDoesNotThrow { gate.requireSafe("AI", 7L, "page load failed") }
        verify(exactly = 0) { repository.reject(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tokenization-only formatting changes are not treated as pii`() {
        val formattingNer = NerMasker { text -> NerMaskingResult(text.replace(" ", "")) }
        val formattingGate = OutboundPrivacyGate(MaskingPipeline(auditRepository, mapper, formattingNer), repository, mapper)

        assertDoesNotThrow { formattingGate.requireSafe("AI", 7L, "rule result is safe") }
        verify(exactly = 0) { repository.reject(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ordinary business record numbers are not mistaken for addresses`() {
        assertDoesNotThrow {
            gate.requireSafe("INDEX", 7L, "跨渠道模拟问题；记录编号 123；教室播放失败。")
        }
        verify(exactly = 0) { repository.reject(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `newly detected outbound sensitive text is rejected without creating review state`() {
        assertThrows(OutboundPrivacyException::class.java) { gate.requireSafe("VECTOR", 7L, "联系电话 13812345678") }
        verify(exactly = 1) { repository.reject("VECTOR", "7", any(), any(), 1) }
    }

    @Test
    fun `outbound rejection audit never persists original sensitive value`() {
        val types = slot<String>()
        assertThrows(OutboundPrivacyException::class.java) { gate.requireSafe("EXPORT", null, "邮箱 secret.person@example.com") }
        verify { repository.reject("EXPORT", "AGGREGATE", any(), capture(types), 1) }
        assertFalse(types.captured.contains("secret.person@example.com"))
    }
}
