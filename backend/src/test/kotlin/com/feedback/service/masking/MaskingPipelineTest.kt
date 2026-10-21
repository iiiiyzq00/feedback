package com.feedback.service.masking

import com.feedback.model.masking.MaskingAudit
import com.feedback.repository.MaskingAuditRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaskingPipelineTest {
    private val repository = mockk<MaskingAuditRepository>()
    private val pipeline = MaskingPipeline(repository, jacksonObjectMapper(), NoopNerMasker)

    @Test
    fun `masks common pii and records evaluation audit`() {
        val audit = slot<MaskingAudit>()
        every { repository.save(capture(audit)) } answers { audit.captured }

        val result = pipeline.mask("联系 13812345678 或 test@example.com")

        assertEquals("联系 [PHONE] 或 [EMAIL]", result.maskedText)
        assertEquals("PASS", result.maskingStatus)
        assertEquals(listOf("PHONE", "EMAIL"), result.findingTypes)
        assertEquals("EVALUATION", audit.captured.auditType)
        assertEquals(MaskingPipeline.RULE_VERSION, audit.captured.maskingRuleVersion)
        assertFalse(audit.captured.evalErrorDetails?.toString().orEmpty().contains("13812345678"))
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `deterministic and contextual rules run before ner`() {
        every { repository.save(any()) } answers { firstArg() }
        var nerInput = ""
        val inspectingNer = object : NerMasker {
            override fun mask(text: String): NerMaskingResult {
                nerInput = text
                return NerMaskingResult(text, emptyList(), true)
            }
        }
        val orderedPipeline = MaskingPipeline(repository, jacksonObjectMapper(), inspectingNer)

        orderedPipeline.mask("姓名：张三，电话13812345678，就读阳光小学")

        assertFalse(nerInput.contains("张三"))
        assertFalse(nerInput.contains("13812345678"))
        assertFalse(nerInput.contains("阳光小学"))
        assertTrue(nerInput.contains("[NAME_REMOVED]"))
        assertTrue(nerInput.contains("[PHONE]"))
        assertTrue(nerInput.contains("某学校"))
    }

    @Test
    fun `compact date marker is not misclassified as a landline`() {
        every { repository.save(any()) } answers { firstArg() }

        val result = pipeline.mask("REHEARSAL-20260805-211034")

        assertEquals("REHEARSAL-20260805-211034", result.maskedText)
        assertFalse(result.findingTypes.contains("LANDLINE"))
    }

    @Test
    fun `jira hyphen timestamp after year is not misclassified as mac`() {
        every { repository.save(any()) } answers { firstArg() }
        val result = pipeline.mask("【出现故障时间(精确到分)】:2025-06-24-18-42-30-01")

        assertEquals("【出现故障时间(精确到分)】:2025-06-24-18-42-30-01", result.maskedText)
        assertFalse(result.findingTypes.contains("MAC"))
    }

    @Test
    fun `genuine mac remains masked after timestamp exception`() {
        every { repository.save(any()) } answers { firstArg() }
        val result = pipeline.mask("设备MAC aa-bb-cc-dd-ee-ff")

        assertTrue(result.maskedText.contains("[MAC_REMOVED]"))
        assertTrue(result.findingTypes.contains("MAC"))
    }

    @Test
    fun `masks full and explicitly labelled local landlines`() {
        every { repository.save(any()) } answers { firstArg() }

        val result = pipeline.mask("固话 010-87654321，座机：87654321")

        assertEquals("固话 [LANDLINE_REMOVED],座机:[LANDLINE_REMOVED]", result.maskedText)
        assertEquals(listOf("LANDLINE"), result.findingTypes)
    }
}
