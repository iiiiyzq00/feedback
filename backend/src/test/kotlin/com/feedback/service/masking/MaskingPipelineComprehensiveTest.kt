package com.feedback.service.masking

import com.feedback.repository.MaskingAuditRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MaskingPipelineComprehensiveTest {
    private val repository = mockk<MaskingAuditRepository>(relaxed = true)
        .also { repository -> every { repository.save(any()) } answers { firstArg() } }
    private val pipeline = MaskingPipeline(repository, jacksonObjectMapper(), NoopNerMasker)

    @Test
    fun `applies ordered credential identity contact and network rules`() {
        val input = "password:abc123 身份证11010519491231002X 手机138-1234-5678 " +
            "邮箱Foo@Example.com IP 192.168.10.22 MAC aa:bb:cc:dd:ee:ff"
        val result = pipeline.mask(input)
        assertEquals("PASS", result.maskingStatus)
        assertFalse(result.maskedText.contains("abc123"))
        assertFalse(result.maskedText.contains("11010519491231002X"))
        assertFalse(result.maskedText.contains("138-1234-5678"))
        assertTrue(result.findingTypes.containsAll(listOf("CREDENTIAL", "ID_CARD", "PHONE", "EMAIL", "IP", "MAC")))
        assertTrue(result.maskedText.contains("192.168.*.*"))
        assertFalse(result.maskedText.contains("$1"))
    }

    @Test
    fun `masks contextual child name school class and address`() {
        val result = pipeline.mask("联系小明妈妈，就读阳光小学三年级2班，住幸福路88号")
        assertFalse(result.maskedText.contains("小明"))
        assertFalse(result.maskedText.contains("阳光小学"))
        assertTrue(result.maskedText.contains("某学校"))
        assertTrue(result.maskedText.contains("某班级"))
        assertTrue(result.maskedText.contains("[ADDRESS_REMOVED]"))
    }

    @Test
    fun `masks conversational student name missed by ner`() {
        val result = pipeline.mask("学生张小明反馈课程页面无法打开")

        assertFalse(result.maskedText.contains("张小明"))
        assertTrue(result.findings.any { it.type == "NAME" && it.originalValue == "张小明" })
    }

    @Test
    fun `masks document device account and precise location identifiers`() {
        val input = "护照 E12345678 IMEI: 490154203237518 学号: STU_20260001 经纬度: 31.230416"

        val result = pipeline.mask(input)

        assertEquals("PASS", result.maskingStatus)
        assertFalse(result.maskedText.contains("E12345678"))
        assertFalse(result.maskedText.contains("490154203237518"))
        assertFalse(result.maskedText.contains("STU_20260001"))
        assertFalse(result.maskedText.contains("31.230416"))
        assertTrue(result.findingTypes.containsAll(listOf("PASSPORT", "DEVICE_ID", "STUDENT_EMPLOYEE_ID", "COORDINATE")))
    }

    @Test
    fun `masks jira device serial labels without allowing unrelated long numbers`() {
        val serials = pipeline.mask("序列号：20260814123456，SN码 12345678901，机器码 1234567890123456789012345，*序列号*: 20260814112233，【序列号】：XYZPQ20260814123456")

        assertEquals("PASS", serials.maskingStatus)
        assertEquals(5, serials.findings.count { it.type == "DEVICE_ID" })
        assertFalse(serials.maskedText.contains("20260814123456"))
        assertFalse(serials.maskedText.contains("12345678901"))
        assertFalse(serials.maskedText.contains("1234567890123456789012345"))

        val unknown = pipeline.mask("未知编号 1234567890 自动脱敏")
        assertEquals("PASS", unknown.maskingStatus)
        assertTrue(unknown.maskedText.contains("[LONG_NUMBER_REMOVED]"))
    }

    @Test
    fun `masks explicitly labelled sample device and does not treat dotted version as ip`() {
        val result = pipeline.mask("【型号与序列号】：SAMPLEDEVICE2023081501（样机），使用400样机SAMPLEDEVICE2023081501复测，系统版本V3.3.1.4")

        assertEquals("PASS", result.maskingStatus)
        assertEquals(2, result.findings.count { it.type == "DEVICE_ID" })
        assertFalse(result.maskedText.contains("SAMPLEDEVICE2023081501"))
        assertTrue(result.maskedText.contains("V3.3.1.4"))
        assertFalse(result.findingTypes.contains("IP"))
        assertFalse(result.residualHighRiskDigits)
    }

    @Test
    fun `masks residual high risk numeric fragment and auto releases`() {
        val result = pipeline.mask("未知编号 1234567890 自动处理")
        assertEquals("PASS", result.maskingStatus)
        assertFalse(result.maskedText.contains("1234567890"))
        assertTrue(result.findingTypes.contains("LONG_NUMBER"))
    }

    @Test
    fun `evaluation audit respects review field exclusivity`() {
        val captured = slot<com.feedback.model.masking.MaskingAudit>()
        every { repository.save(capture(captured)) } answers { firstArg() }

        pipeline.mask("普通反馈")

        assertEquals("EVALUATION", captured.captured.auditType)
        assertNull(captured.captured.reviewConclusion)
        assertNull(captured.captured.reviewer)
        assertNotNull(captured.captured.evaluationKey)
        assertEquals(1, captured.captured.evalTotalSamples)
    }

    @Test
    fun `ner outage does not create a human review dependency`() {
        val unavailable = NerMasker { text -> NerMaskingResult(text, available = false) }
        val guarded = MaskingPipeline(repository, jacksonObjectMapper(), unavailable)

        val result = guarded.mask("需要检查这条普通文本")

        assertEquals("PASS", result.maskingStatus)
        assertFalse(result.nerAvailable)
    }
}
