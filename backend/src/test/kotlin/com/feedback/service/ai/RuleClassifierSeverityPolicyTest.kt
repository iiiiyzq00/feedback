package com.feedback.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RuleClassifierSeverityPolicyTest {
    private val classifier = RuleClassifier()

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "所有用户都无法登录服务,S0",
        "学习记录数据丢失且无法恢复,S0",
        "设备出现冒烟现象,S0",
        "AI助教点击后持续闪退,S1",
        "屏幕故障需要换机,S1",
        "数学答案解析错误,S2",
        "视频播放明显卡顿,S2",
        "希望增加夜间模式,S3",
        "这个版本非常好用,S4"
    )
    fun `classifies severity from governed impact definitions`(description: String, expected: String) {
        val result = classifier.classify(null, description)

        assertEquals(expected, result.severity)
        assertTrue("SEVERITY_POLICY_V1" in result.evidenceCodes)
        assertEquals("rule-cascade-v2", result.modelVersion)
    }

    @ParameterizedTest
    @CsvSource(
        "登录失败,S2",
        "有点生气但功能可以正常使用,S3",
        "整体不错但是打开后闪退,S1"
    )
    fun `does not promote severity from emotion or one ambiguous word`(description: String, expected: String) {
        assertEquals(expected, classifier.classify(null, description).severity)
    }
}
