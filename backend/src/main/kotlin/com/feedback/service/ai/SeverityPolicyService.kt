package com.feedback.service.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import com.feedback.repository.ai.SeverityPolicyRepository

/**
 * 严重度策略运行时服务。
 *
 * 策略以版本化配置管理（config_type='SEVERITY_POLICY'），激活版本从数据库读取；
 * 数据库不可用或未配置时回退到内嵌的 v1 历史基线，保证降级环境行为与旧版本一致。
 * v1 同时作为历史版本保留（内嵌常量 + V168 迁移写入的历史行），可回滚。
 *
 * 规则引擎语义（各版本一致）：
 *   S0 = 安全/隐私/数据丢失(不可恢复) 或 大范围核心不可用；
 *   S1 = 数据丢失 或 严重故障 或 硬件需送修换机 或 核心不可用；
 *   S2 = 明显降级（存在替代路径）；
 *   S4 = 纯表扬（category=PRAISE）；
 *   其余 S3。
 * 各版本的差异体现在 rubric 文案、等级定义和关键词集合（rules）上。
 */
@Component
class SeverityPolicyService(
    private val policies: SeverityPolicyRepository?,
    private val mapper: ObjectMapper
) {
    private data class CacheEntry(val def: SeverityPolicyDef, val loadedAt: Long)

    @Volatile
    private var cache: CacheEntry? = null

    fun active(): SeverityPolicyDef = cache?.takeIf { System.currentTimeMillis() - it.loadedAt < CACHE_TTL_MS }?.def
        ?: load().also { cache = it }.def

    fun version(): String = active().policyVersion

    fun rubric(): String = active().rubric

    fun levels(): List<AiSeverityDefinition> = active().levels

    fun evidenceCode(): String = active().evidenceCode

    /** 按当前激活策略判定严重度；可显式传入版本用于评测/测试。 */
    fun classifySeverity(text: String, category: String, def: SeverityPolicyDef = active()): String {
        return classifyWith(text, category, def)
    }

    private fun load(): CacheEntry {
        val parsed = runCatching {
            policies?.activeJson()
        }.getOrNull()?.let { raw ->
            runCatching { mapper.readValue(raw, SeverityPolicyDef::class.java) }.getOrNull()
        }
        return CacheEntry(parsed ?: V1_DEF, System.currentTimeMillis())
    }

    companion object {
        const val CACHE_TTL_MS = 60_000L

        /** 无服务实例时的 v1 静态回退（供 RuleClassifier 等无依赖场景使用）。 */
        fun classifySeverityFallback(text: String, category: String): String = classifyWith(text, category, V1_DEF)

        internal fun classifyWith(text: String, category: String, def: SeverityPolicyDef): String {
        val t = text.lowercase()
        val has = { keywords: List<String> -> keywords.any { t.contains(it) } }
        val r = def.rules
        val safetyOrSecurity = has(r.safetyOrSecurity)
        val dataLoss = has(r.dataLoss)
        val irreversible = has(r.irreversible)
        val broadImpact = has(r.broadImpact)
        val coreUnavailable = has(r.coreUnavailable)
        val severeFailure = has(r.severeFailure)
        val repairRequired = has(r.repairRequired)
        val degradedFailure = has(r.degradedFailure)
        return when {
            safetyOrSecurity || (dataLoss && irreversible) || (broadImpact && coreUnavailable) -> "S0"
            dataLoss || severeFailure || repairRequired || coreUnavailable -> "S1"
            degradedFailure -> "S2"
            category == "PRAISE" -> "S4"
            else -> "S3"
        }
        }

        /** v1 历史基线（与旧 RuleClassifier/SeverityPolicy 语义一致）。 */
        val V1_RULES = SeverityRuleSet(
            safetyOrSecurity = listOf("起火", "冒烟", "触电", "账号泄露", "隐私泄露", "数据泄露", "远程控制"),
            dataLoss = listOf("数据丢失", "数据清空", "记录丢失"),
            irreversible = listOf("无法恢复", "不可恢复", "永久丢失", "全部丢失", "数据清空"),
            broadImpact = listOf("大面积", "全量", "全部用户", "所有用户", "普遍", "全校", "全班"),
            coreUnavailable = listOf("无法登录", "无法开机", "无法启动", "无法使用", "服务不可用", "全部无法"),
            severeFailure = listOf("闪退", "崩溃", "卡死", "无响应", "无法使用", "无法开机", "无法启动"),
            repairRequired = listOf("换机", "退机", "返修", "维修"),
            degradedFailure = listOf("卡顿", "失败", "错误", "结果不对", "答案错", "题目错", "内容错", "解析错"),
        )

        val V1_DEF = SeverityPolicyDef(
            policyVersion = SeverityPolicy.VERSION,
            basedOn = null,
            evidenceCode = "SEVERITY_POLICY_V1",
            rubric = SeverityPolicy.llmRubric,
            levels = AiClassificationTaxonomy.severities,
            rules = V1_RULES,
        )

        /** v2 激活策略（用户视角，2026-08-06 修订；与 V168 迁移写入的配置一致）。 */
        val V2_RULES = SeverityRuleSet(
            safetyOrSecurity = listOf("漏电", "冒烟", "起火", "过热", "触电", "账号泄露", "隐私泄露", "数据泄露", "远程控制"),
            dataLoss = listOf("数据丢失", "数据清空", "记录丢失", "学习记录丢失"),
            irreversible = listOf("无法恢复", "不可恢复", "永久丢失", "全部丢失", "数据清空"),
            broadImpact = listOf("大面积", "全量", "全部用户", "所有用户", "普遍", "全校", "全班"),
            coreUnavailable = listOf("无法登录", "登录不上", "无法开机", "无法启动", "无法使用", "服务不可用", "全部无法", "卡logo", "卡在logo", "进不了系统", "无法进入"),
            severeFailure = listOf("闪退", "崩溃", "卡死", "无响应", "无法使用", "无法开机", "无法启动", "自动关机", "黑屏", "上课闪退"),
            repairRequired = listOf("换机", "退机", "返修", "维修", "送修", "返厂", "碎屏", "屏裂", "花屏", "触屏", "无法充电", "黑屏无显示"),
            degradedFailure = listOf("卡顿", "失败", "错误", "结果不对", "答案错", "题目错", "内容错", "解析错", "排版", "不更新", "不同步", "断网", "白屏", "杂音", "偏暗", "变形", "漏光", "发红"),
        )

        val V2_DEF = SeverityPolicyDef(
            policyVersion = "severity-policy-v2",
            basedOn = "severity-policy-v1",
            evidenceCode = "SEVERITY_POLICY_V2",
            rubric = """
                严重度必须遵循 severity-policy-v2，回答"这条反馈对用户今天的学习/使用有多大影响"，按影响而不是情绪或单个关键词判断；多个等级同时满足时取最高等级：
                - S0 致命：人身安全（漏电/冒烟/起火/过热/触电）、账号或隐私泄露、不可恢复的数据丢失，或大范围（全班/全校/所有用户）核心能力不可用。
                - S1 严重：学习被完全阻断（无法开机/卡 logo/进不了系统/上课闪退/自动关机/无法登录进课程），或硬件需送修换机（碎屏/无法充电/黑屏无显示/触屏完全失效/花屏无法显示）；影响尚未达到 S0。
                - S2 中等：明显降级但有替代路径，例如断网可开热点、白屏重试可恢复、内容错误、数据不同步、卡顿明显影响体验、音频或按键可用替代方式；外观类硬件问题（外壳变形、显示偏暗/发红/漏光、杂音）按可继续使用判 S2。
                - S3 轻微：非阻断体验问题（加载慢/文案不清/按钮位置）、一般咨询、需求建议，或无故障证据的投诉。
                - S4 无影响：纯表扬或正向反馈，不包含任何故障、风险或改进诉求。
                判断时必须结合影响范围、学习阻断程度、可恢复性、替代路径、安全风险和复现情况。证据不足不得上调；不得把负面情绪直接当作高严重度，也不得把含故障的正向表述判为 S4。
                explanation 必须说明命中的影响条件；evidence_codes 至少包含 SEVERITY_POLICY_V2 和对应等级代码 SEVERITY_S0 至 SEVERITY_S4。
            """.trimIndent(),
            levels = listOf(
                AiSeverityDefinition("S0", "致命", "人身安全（漏电/冒烟/起火/过热/触电）、账号或隐私泄露、不可恢复的数据丢失，或大范围核心能力不可用"),
                AiSeverityDefinition("S1", "严重", "学习被完全阻断（无法开机/卡logo/进不了系统/上课闪退/自动关机/无法登录），或硬件需送修换机"),
                AiSeverityDefinition("S2", "中等", "明显降级但有替代路径（断网可热点/白屏可重试/内容错误/数据不同步/卡顿明显影响体验）"),
                AiSeverityDefinition("S3", "轻微", "非阻断体验问题、一般咨询、需求建议或无故障证据的投诉"),
                AiSeverityDefinition("S4", "无故障影响", "纯表扬或正向反馈，不包含故障、风险或改进诉求"),
            ),
            rules = V2_RULES,
        )
    }
}

data class SeverityRuleSet(
    val safetyOrSecurity: List<String> = emptyList(),
    val dataLoss: List<String> = emptyList(),
    val irreversible: List<String> = emptyList(),
    val broadImpact: List<String> = emptyList(),
    val coreUnavailable: List<String> = emptyList(),
    val severeFailure: List<String> = emptyList(),
    val repairRequired: List<String> = emptyList(),
    val degradedFailure: List<String> = emptyList(),
)

data class SeverityPolicyDef(
    val policyVersion: String,
    val basedOn: String? = null,
    val evidenceCode: String,
    val rubric: String,
    val levels: List<AiSeverityDefinition>,
    val rules: SeverityRuleSet = SeverityRuleSet(),
)
