package com.feedback.service.masking

import com.feedback.repository.MaskingAuditRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 独立影子测试源集不受仓库既有 test 编译断裂影响，用于守住脱敏安全与业务语义两条边界。 */
class MaskingSemanticPreservingTest {
    private val audits = mockk<MaskingAuditRepository>(relaxed = true).also {
        every { it.save(any()) } answers { firstArg() }
    }
    private val pipeline = MaskingPipeline(audits, jacksonObjectMapper(), NoopNerMasker)

    @Test
    fun `手机号身份证和儿童身份继续严格脱敏`() {
        val result = pipeline.mask(
            "学生张小明反馈题目错误,就读阳光小学三年级2班,电话13812345678,身份证11010519491231002X"
        )

        assertFalse(result.maskedText.contains("张小明"))
        assertFalse(result.maskedText.contains("阳光小学"))
        assertFalse(result.maskedText.contains("三年级2班"))
        assertFalse(result.maskedText.contains("13812345678"))
        assertFalse(result.maskedText.contains("11010519491231002X"))
        assertTrue(result.findingTypes.containsAll(listOf("NAME", "SCHOOL", "CLASS", "PHONE", "ID_CARD")))
    }

    @Test
    fun `题目时间和绘本名保留而姓名语境与长数字继续保守脱敏`() {
        val result = pipeline.mask(
            "题目10273里11点到1点不是三个小时,绘本百变马丁无法识别,白色的老鼠叫映狐,业务编号123456789012345"
        )

        assertTrue(result.maskedText.contains("10273"))
        assertTrue(result.maskedText.contains("11点到1点"))
        assertTrue(result.maskedText.contains("百变马丁"))
        assertFalse(result.maskedText.contains("映狐"))
        assertFalse(result.maskedText.contains("123456789012345"))
        assertTrue(result.findingTypes.any { it in setOf("LONG_NUMBER", "IMEI_IMSI") })
    }

    @Test
    fun `明确标注的设备号银行卡和凭证仍然脱敏`() {
        val result = pipeline.mask(
            "IMEI:123456789012345,银行卡号:6222021234567890123,password:secret-value"
        )

        assertFalse(result.maskedText.contains("123456789012345"))
        assertFalse(result.maskedText.contains("6222021234567890123"))
        assertFalse(result.maskedText.contains("secret-value"))
        assertTrue(result.findingTypes.any { it in setOf("DEVICE_ID", "IMEI_IMSI") })
        assertTrue(result.findingTypes.containsAll(listOf("BANK_CARD", "CREDENTIAL")))
    }
}
