package com.feedback.service.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeverityPolicyServiceTest {
    private val mapper = ObjectMapper()
    private val service = SeverityPolicyService(null, mapper)

    @Test
    fun `falls back to v1 historical baseline when no database`() {
        assertEquals("severity-policy-v1", service.version())
        assertEquals("SEVERITY_POLICY_V1", service.evidenceCode())
        assertTrue(service.rubric().contains("severity-policy-v1"))
        assertEquals(5, service.levels().size)
    }

    @Test
    fun `v1 rules preserve historical semantics`() {
        assertEquals("S0", service.classifySeverity("所有用户都无法登录服务", "BUG", SeverityPolicyService.V1_DEF))
        assertEquals("S1", service.classifySeverity("屏幕故障需要换机", "HARDWARE", SeverityPolicyService.V1_DEF))
        assertEquals("S2", service.classifySeverity("视频播放明显卡顿", "BUG", SeverityPolicyService.V1_DEF))
        assertEquals("S3", service.classifySeverity("希望增加夜间模式", "DEMAND", SeverityPolicyService.V1_DEF))
        assertEquals("S4", service.classifySeverity("这个版本非常好用", "PRAISE", SeverityPolicyService.V1_DEF))
    }

    @Test
    fun `v2 rules reflect user perspective severity`() {
        val v2 = SeverityPolicyService.V2_DEF
        assertEquals("severity-policy-v2", v2.policyVersion)
        assertEquals("severity-policy-v1", v2.basedOn)
        assertEquals("SEVERITY_POLICY_V2", v2.evidenceCode)

        // S0: 安全/隐私
        assertEquals("S0", service.classifySeverity("希沃漏电了，存在安全隐患", "HARDWARE", v2))
        // S1: 学习阻断 / 硬件送修
        assertEquals("S1", service.classifySeverity("学生平板碎屏，申请返厂维修", "HARDWARE", v2))
        assertEquals("S1", service.classifySeverity("设备黑屏无法亮屏", "BUG", v2))
        assertEquals("S1", service.classifySeverity("登录不上希沃白板", "BUG", v2))
        assertEquals("S1", service.classifySeverity("AI辅导作业批改一直加载无法使用", "BUG", v2))
        // S2: 明显降级但有替代路径
        assertEquals("S2", service.classifySeverity("快速切换画笔颜色时画布出现卡顿", "BUG", v2))
        assertEquals("S2", service.classifySeverity("学习机连接家里WiFi经常断网", "BUG", v2))
        assertEquals("S2", service.classifySeverity("机身弯曲变形，仍可继续使用", "HARDWARE", v2))
        // S3: 非阻断体验/建议
        assertEquals("S3", service.classifySeverity("清空画布按钮的位置不够容易发现", "DEMAND", v2))
        assertEquals("S3", service.classifySeverity("希望增加夜间模式", "DEMAND", v2))
        // S4: 纯表扬
        assertEquals("S4", service.classifySeverity("这个版本非常好用", "PRAISE", v2))
    }

    @Test
    fun `v2 taxonomy contract exposes user perspective severity definitions`() {
        val contract = AiClassificationTaxonomy.contract(SeverityPolicyService.V2_DEF.levels)
        assertEquals(5, contract.severities.size)
        assertEquals("S0", contract.severities.first().code)
        assertTrue(contract.severities.first().definition.contains("漏电"))
        assertEquals("S4", contract.severities.last().code)
    }
}
