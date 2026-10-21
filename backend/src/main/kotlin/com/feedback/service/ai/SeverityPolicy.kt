package com.feedback.service.ai

/**
 * severity-policy-v1 历史基线（保留用于回滚、测试与降级回退）。
 *
 * 运行时激活版本由 [SeverityPolicyService] 从配置读取（当前为 v2），
 * 不要在这里直接修改定义；新增版本请走 V168 之后的配置迁移与
 * `SeverityPolicyService` 内嵌定义。
 */
object SeverityPolicy {
    const val VERSION = "severity-policy-v1"

    val llmRubric = """
        严重度必须遵循 $VERSION，并按影响而不是情绪或单个关键词判断；多个等级同时满足时取最高等级：
        - S0 致命：大范围核心服务不可用、不可恢复的数据丢失，或明确的人身安全/账号与隐私泄露风险。
        - S1 严重：核心功能对受影响用户不可用，持续崩溃/卡死/无法启动，或硬件故障需要维修换机；影响尚未达到 S0。
        - S2 中等：重要功能错误或明显降级，例如卡顿、操作失败、结果/内容错误；通常存在替代路径且不满足 S0/S1。
        - S3 轻微：非阻断体验问题、一般咨询、需求建议或投诉，但没有证据证明核心功能不可用。
        - S4 无故障影响：纯表扬或正向反馈，且不包含任何故障、风险或改进诉求。
        判断时必须结合影响范围、核心功能、可恢复性、替代路径、安全风险和复现情况。证据不足不得上调；不得把负面情绪直接当作高严重度，也不得把含故障的正向表述判为 S4。
        explanation 必须说明命中的影响条件；evidence_codes 至少包含 SEVERITY_POLICY_V1 和对应等级代码 SEVERITY_S0 至 SEVERITY_S4。
    """.trimIndent()
}
