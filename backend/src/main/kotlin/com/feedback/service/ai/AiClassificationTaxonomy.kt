package com.feedback.service.ai

data class AiCategoryDefinition(
    val code: String,
    val label: String,
    val definition: String,
    val positiveExamples: List<String>,
    val excludes: String
)

data class AiSeverityDefinition(val code: String, val label: String, val definition: String)

data class AiTaxonomyContract(
    val version: String,
    val compatibleFrom: List<String>,
    val unknownPolicy: String,
    val categories: List<AiCategoryDefinition>,
    val severities: List<AiSeverityDefinition>
)

/** 推理、复核与 UI 共用的稳定分类契约，可直接用于展示。 */
object AiClassificationTaxonomy {
    const val VERSION = "ai-taxonomy-v1"
    val categories = listOf(
        AiCategoryDefinition("BUG", "程序缺陷", "软件功能异常、崩溃、卡死或结果不符合既定设计", listOf("闪退", "页面无响应", "功能执行错误"), "硬件损坏、内容本身错误"),
        AiCategoryDefinition("HARDWARE", "硬件问题", "设备部件、外设、结构或物理性能异常", listOf("麦克风无声", "屏幕触控失灵", "电池异常"), "纯软件权限或应用崩溃"),
        AiCategoryDefinition("CONTENT_ERROR", "内容错误", "题目、答案、解析、课程或知识内容存在事实错误", listOf("答案错误", "解析与题目不符", "课程内容缺失"), "软件渲染失败、一般需求建议"),
        AiCategoryDefinition("DEMAND", "需求建议", "当前能力未承诺，用户提出新增或改进诉求", listOf("希望增加功能", "建议调整交互"), "已有功能失效"),
        AiCategoryDefinition("CONSULT", "使用咨询", "用户询问使用方式、规则或信息，未陈述明确故障", listOf("如何使用", "在哪里设置"), "未知或无法判断的故障不得强行归入咨询"),
        AiCategoryDefinition("PRAISE", "正向反馈", "仅表达认可、表扬或正向体验且不含问题诉求", listOf("体验很好", "功能很实用"), "夹带故障或投诉时按主要问题分类"),
        AiCategoryDefinition("COMPLAINT", "投诉反馈", "表达强烈不满、服务投诉或升级处理诉求", listOf("要求投诉", "多次反馈仍未解决"), "普通故障描述优先归入具体问题类别")
    )
    val severities = listOf(
        AiSeverityDefinition("S0", "致命", "大范围核心不可用、不可恢复数据损失或安全隐私风险"),
        AiSeverityDefinition("S1", "严重", "核心功能不可用、频繁崩溃或需立即处置的硬件故障"),
        AiSeverityDefinition("S2", "中等", "重要功能错误或明显降级，但存在替代路径"),
        AiSeverityDefinition("S3", "轻微", "非阻断体验问题、一般咨询或需求建议"),
        AiSeverityDefinition("S4", "无故障影响", "纯正向反馈且没有故障或风险")
    )
    val categoryCodes = categories.mapTo(linkedSetOf()) { it.code }
    val severityCodes = severities.mapTo(linkedSetOf()) { it.code }

    private val legacyAliases = mapOf(
        "SOFTWARE_BUG" to "BUG", "CONTENT" to "CONTENT_ERROR", "SUGGESTION" to "DEMAND",
        "QUESTION" to "CONSULT", "POSITIVE" to "PRAISE", "CLAIM" to "COMPLAINT"
    )

    fun normalizeCategory(value: String): String? {
        val normalized = value.trim().uppercase()
        return when {
            normalized in categoryCodes -> normalized
            normalized in legacyAliases -> legacyAliases.getValue(normalized)
            else -> null
        }
    }

    fun contract(severities: List<AiSeverityDefinition> = this.severities) = AiTaxonomyContract(
        version = VERSION,
        compatibleFrom = listOf("legacy-unversioned", VERSION),
        unknownPolicy = "无法映射的类别不得自动写入；进入失败重试或人工复核，不使用 CONSULT 作为未知兜底",
        categories = categories,
        severities = severities
    )
}
