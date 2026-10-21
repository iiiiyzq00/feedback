package com.feedback.service.ai

import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class RuleClassifier(
    private val severityPolicy: SeverityPolicyService? = null
) {
    fun classify(title: String?, description: String, degraded: Boolean = false): AiClassification {
        val text = "${title.orEmpty()} ${description}".lowercase()
        val (category, categoryEvidence) = when {
            listOf("闪退", "崩溃", "crash", "exception", "卡死", "无响应", "卡顿", "黑屏").any(text::contains) -> "BUG" to "RULE_RUNTIME_FAILURE"
            listOf("麦克风", "摄像头", "屏幕", "电池", "硬件").any(text::contains) -> "HARDWARE" to "RULE_HARDWARE"
            listOf("答案错", "题目错", "内容错", "解析错").any(text::contains) -> "CONTENT_ERROR" to "RULE_CONTENT"
            listOf("建议", "希望", "需要", "增加").any(text::contains) -> "DEMAND" to "RULE_DEMAND"
            listOf("赞", "好用", "很棒").any(text::contains) -> "PRAISE" to "RULE_PRAISE"
            listOf("投诉", "垃圾", "太差").any(text::contains) -> "COMPLAINT" to "RULE_COMPLAINT"
            else -> "CONSULT" to "RULE_DEFAULT"
        }
        val severity = severityPolicy?.classifySeverity(text, category) ?: SeverityPolicyService.classifySeverityFallback(text, category)
        val sentiment = when {
            listOf("垃圾", "太差", "投诉", "生气", "失望").any(text::contains) -> "NEGATIVE"
            listOf("赞", "好用", "很棒", "满意").any(text::contains) -> "POSITIVE"
            else -> "NEUTRAL"
        }
        val confidence = when {
            categoryEvidence == "RULE_DEFAULT" -> BigDecimal("0.550000")
            severity in setOf("S0", "S1") -> BigDecimal("0.920000")
            severity == "S4" -> BigDecimal("0.900000")
            else -> BigDecimal("0.880000")
        }
        val normalized = description.replace(Regex("\\s+"), " ").trim()
        return AiClassification(
            summary = normalized.take(200).ifBlank { "用户未提供有效问题描述" },
            category = category,
            severity = severity,
            sentiment = sentiment,
            confidence = confidence,
            explanation = if (degraded) "LLM 不可用，按 ${severityPolicy?.version() ?: SeverityPolicy.VERSION} 使用确定性规则降级" else "确定性规则命中 $categoryEvidence，并按 ${severityPolicy?.version() ?: SeverityPolicy.VERSION} 判定 $severity",
            evidenceCodes = listOf(categoryEvidence, "RULE_SEVERITY_$severity", severityPolicy?.evidenceCode() ?: SeverityPolicyService.V1_DEF.evidenceCode),
            modelVersion = if (degraded) "rule-fallback-v2" else "rule-cascade-v2",
            degraded = degraded
        )
    }
}
