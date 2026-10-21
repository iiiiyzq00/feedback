package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.RcaFallbackEdge
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus

/**
 * 节点执行上下文。刻意只暴露「状态 + 第几次进入」，不暴露数据库或上游客户端：
 * 节点要保持可单独复算，否则固定回放就退化成端到端集成测试，出了问题无法定位到节点。
 */
data class RcaNodeContext(
    val state: RcaSharedState,
    /** 同一节点的第几次进入。回退边会让 [RcaNode.ROOT_CAUSE_ARBITRATE] 多次进入。 */
    val attemptNo: Int,
    /**
     * 本次任务执行的链路标识，由节点原样透传给工具调用边界（`ToolInvocationGuard`）用于落库。
     *
     * ## 它为什么可以进这个上下文，而不违反「只暴露状态 + 第几次进入」
     *
     * 上面那条约束的目的是**节点必须能脱离数据库与调度单独复算**，判据是
     * 「这个值会不会影响节点的判断」。链路标识不会：它不参与任何分支、不进共享状态、
     * 不参与任何哈希或幂等键的推导——它只是被原样带下去，贴到 `t_rca_tool_call` 的行上。
     * 换句话说，两个除了链路标识外完全相同的上下文，在任何节点上都必须产出完全相同的结果；
     * **节点不得依据它做任何判断**。这一点不是君子协定：把标识放进共享状态会立刻
     * 破坏固定回放（快照逐字节相等是断言的一部分），而不是破坏这条约定——
     * 这也是它没有放进 [RcaSharedState] 的原因。
     *
     * 为 null 表示这次执行没有链路标识（离线夹具构造的任务，或者早于该列引入的历史行）。
     * 此时工具调用边界**不做**链路落库，而不是退回成编一个出来。
     */
    val traceId: String? = null
)

/**
 * 节点执行结果。
 *
 * [next] 为 null 表示流程结束；[fallback] 非空表示走的不是前向推进而是回退边，
 * 两者必须显式区分，因为回退次数是有限额的（[RcaFallbackEdge.MAX_ARBITRATE_REVISIONS]），
 * 混在一起会让回退限额失效并形成活锁。
 */
data class RcaNodeOutcome(
    val state: RcaSharedState,
    val next: RcaNode?,
    /** 进入该节点的确定性依据，落库到 `t_rca_agent_step.entered_reason`。 */
    val enterReason: String,
    /** 离开该节点的原因，用于区分「路由选错」与「证据不足」。 */
    val leaveReason: String,
    val fallback: RcaFallbackEdge? = null,
    /** 该节点直接判定任务终态；非空时忽略 [next]。 */
    val terminalStatus: RcaTaskStatus? = null,
    val stopReason: RcaStopReason? = null
)

/**
 * 节点处理器。五个主节点各自实现一个，编排器只认这个接口。
 *
 * 约束写在接口层面：[RcaNode.modelDriven] 为 false 的节点不得调用模型。
 * 实现方如果需要在确定性节点里用模型，说明它其实应该被拆到
 * [RcaNode.AGENT_INVESTIGATE] 里，而不是绕开这条边界。
 */
interface RcaNodeHandler {

    val node: RcaNode

    fun execute(context: RcaNodeContext): RcaNodeOutcome
}
