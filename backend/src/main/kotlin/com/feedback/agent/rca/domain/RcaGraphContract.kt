package com.feedback.agent.rca.domain

/**
 * 受限 Agentic RCA 的五个主节点。
 *
 * 设计要点：外层的上下文构建、必要证据计划、仲裁和持久化都是确定性程序，只有
 * [AGENT_INVESTIGATE] 交给模型决策。因此 [modelDriven] 必须能一眼看出边界，
 * 编排层据此决定是否允许调用模型、是否允许消耗模型预算。
 *
 * 节点编码要落库（`t_rca_agent_step.node_code`、`t_rca_agent_task.current_node`），
 * 属于稳定协议字段，因此保持英文大写下划线，不随界面文案变化。
 */
enum class RcaNode(
    /** 落库编码，与 V242 迁移的 CHECK 约束一一对应。 */
    val storageCode: String,
    /** 该节点是否由模型驱动。为 false 表示纯确定性计算，不得调用模型。 */
    val modelDriven: Boolean
) {
    /** 组装问题画像：聚合 Episode 成员、错误签名分布、影响面基线，产出入参快照。 */
    CONTEXT_BUILD("CONTEXT_BUILD", false),

    /** 执行强制证据计划：有合法日志 ID 必须查日志，Crash/ANR 必须取堆栈。 */
    BASELINE_EVIDENCE("BASELINE_EVIDENCE", false),

    /** 动态调查循环：提出假设→选工具→读 Observation→判断支持/反驳→修正→继续或停止。 */
    AGENT_INVESTIGATE("AGENT_INVESTIGATE", true),

    /** 仲裁：校验 claim 与证据的引用支持关系、覆盖范围、时序与机制链、反证处理。 */
    ROOT_CAUSE_ARBITRATE("ROOT_CAUSE_ARBITRATE", false),

    /** 持久化归因、证据账本与知识候选；此节点不产生新的调查动作。 */
    RCA_PERSIST("RCA_PERSIST", false);

    companion object {
        private val BY_CODE = entries.associateBy(RcaNode::storageCode)

        /** 按落库编码解析；未知编码返回 null，由调用方决定是降级还是报错。 */
        fun fromStorage(code: String?): RcaNode? = code?.let(BY_CODE::get)

        /** 正常前向推进顺序；回退边独立建模，不在此列表中。 */
        val FORWARD_ORDER: List<RcaNode> = listOf(
            CONTEXT_BUILD, BASELINE_EVIDENCE, AGENT_INVESTIGATE, ROOT_CAUSE_ARBITRATE, RCA_PERSIST
        )
    }
}

/**
 * 状态图里的回退边。
 *
 * 文档口径是「证据不足或反证排除不了就转人工，不让模型硬凑答案」，所以回退边不是
 * 任意跳转：仲裁失败只能回到 [RcaNode.AGENT_INVESTIGATE] 继续补证，且补证次数受
 * [MAX_ARBITRATE_REVISIONS] 限制。超过上限必须降级为 PARTIAL / NEEDS_HUMAN，
 * 避免出现「仲裁不通过就无限重试」的活锁。
 */
enum class RcaFallbackEdge(val from: RcaNode, val to: RcaNode, val trigger: String) {
    /** 强制证据未满足：回到调查循环补齐，例如日志查询超时后换时间窗重试。 */
    MANDATORY_INCOMPLETE(RcaNode.ROOT_CAUSE_ARBITRATE, RcaNode.AGENT_INVESTIGATE, "MANDATORY_EVIDENCE_INCOMPLETE"),

    /** 仍有未处理的强反证：必须继续取证或明确记录无法排除。 */
    REFUTATION_UNRESOLVED(RcaNode.ROOT_CAUSE_ARBITRATE, RcaNode.AGENT_INVESTIGATE, "REFUTATION_UNRESOLVED"),

    /** 引用支持校验失败（例如范围性结论引用了单点证据）：补 cohort 级证据。 */
    CITATION_UNSUPPORTED(RcaNode.ROOT_CAUSE_ARBITRATE, RcaNode.AGENT_INVESTIGATE, "CITATION_UNSUPPORTED");

    companion object {
        /** 仲裁节点最多允许的回退轮次；超过即降级，防止活锁。 */
        const val MAX_ARBITRATE_REVISIONS: Int = 2

        fun triggersFrom(node: RcaNode): List<RcaFallbackEdge> = entries.filter { it.from == node }
    }
}

/**
 * 任务终态与降级形态。
 *
 * [PARTIAL] 与 [NEEDS_HUMAN] 是设计里的一等公民，不是异常分支：证据不足以支撑结论时
 * 输出 PARTIAL，强制证据拿不到或反证无法排除时输出 NEEDS_HUMAN。两者都必须携带
 * [RcaStopReason]，禁止用空结论冒充完成。
 */
enum class RcaTaskStatus(val storageCode: String, val terminal: Boolean) {
    PENDING("PENDING", false),
    RUNNING("RUNNING", false),
    RETRY_PENDING("RETRY_PENDING", false),

    /** 正常完成：强制证据齐备、引用支持通过、反证已处理。 */
    COMPLETED("COMPLETED", true),

    /** 部分完成：有可用证据但覆盖不足，结论只能作为候选。 */
    PARTIAL("PARTIAL", true),

    /** 转人工：必要现场证据缺失或反证无法排除，模型不得继续给高置信结论。 */
    NEEDS_HUMAN("NEEDS_HUMAN", true),

    FAILED("FAILED", true),
    CANCELLED("CANCELLED", true);

    companion object {
        private val BY_CODE = entries.associateBy(RcaTaskStatus::storageCode)

        fun fromStorage(code: String?): RcaTaskStatus? = code?.let(BY_CODE::get)
    }
}

/**
 * 结构化停止原因。
 *
 * 停止必须由编排器落定而不是模型自述，所以原因是可枚举的机器码，与 V242 迁移的
 * `chk_rca_agent_stop_reason` 约束一致。可观测性上这些码直接对应「错误要能落到编排节点」：
 * [BUDGET_EXHAUSTED] 归到预算控制，[MANDATORY_TOOL_UNAVAILABLE] 归到工具异常，
 * [UNRESOLVED_REFUTATION] 归到仲裁漏检。
 */
enum class RcaStopReason(val storageCode: String) {
    EVIDENCE_SUFFICIENT("EVIDENCE_SUFFICIENT"),
    LOW_INFORMATION_GAIN("LOW_INFORMATION_GAIN"),
    BUDGET_EXHAUSTED("BUDGET_EXHAUSTED"),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED"),
    MAX_STEPS("MAX_STEPS"),
    MANDATORY_TOOL_UNAVAILABLE("MANDATORY_TOOL_UNAVAILABLE"),
    UNRESOLVED_REFUTATION("UNRESOLVED_REFUTATION"),
    NO_VIABLE_TOOL("NO_VIABLE_TOOL"),
    HUMAN_REQUIRED("HUMAN_REQUIRED"),
    CANCELLED("CANCELLED");

    companion object {
        private val BY_CODE = entries.associateBy(RcaStopReason::storageCode)

        fun fromStorage(code: String?): RcaStopReason? = code?.let(BY_CODE::get)
    }
}
