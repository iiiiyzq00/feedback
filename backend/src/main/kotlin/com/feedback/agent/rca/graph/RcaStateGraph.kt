package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.RcaFallbackEdge
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus

/**
 * 单步执行的留痕。落库到 `t_rca_agent_step`，也是回放夹具断言的主要对象。
 */
data class RcaStepTrace(
    val node: RcaNode,
    /** 同一节点的第几次进入；回退会让仲裁节点出现 attemptNo 大于 0 的记录。 */
    val attemptNo: Int,
    val enterReason: String,
    val leaveReason: String,
    /** 非空表示这一步走的是回退边，值即 `t_rca_agent_step` 的 trigger。 */
    val fallbackTrigger: String?,
    val stateVersionBefore: Long,
    val stateVersionAfter: Long,
    val elapsedMs: Long
)

/**
 * 一次完整运行的结果。
 *
 * [status] 只可能是终态。注意它与「运行是否成功」无关：
 * [RcaTaskStatus.PARTIAL] 和 [RcaTaskStatus.NEEDS_HUMAN] 都是**正常产出**，
 * 它们表达的是「证据不足以支撑结论」而不是程序出错。
 */
data class RcaRunResult(
    val status: RcaTaskStatus,
    val stopReason: RcaStopReason,
    val finalState: RcaSharedState,
    val steps: List<RcaStepTrace>,
    /** 每个节点被进入过几次，用于可观测性与「回退是否打转」的判断。 */
    val nodeVisits: Map<RcaNode, Int>,
    /** 实际发生的回退次数。达到上限说明结论是被额度截断的，而不是证据充分。 */
    val arbitrateRevisions: Int,
    /**
     * 本次运行是否在节点边界被调用方中止。
     *
     * 为 true 时 [status] 与 [stopReason] 只是**本地**收口码，不代表任务已经终止：
     * 中止的原因通常是租约已丢失，任务很可能正在另一个 Worker 手里继续跑。
     * 因此调用方在这条路径上必须**整体丢弃** [finalState]，绝不能写回。
     */
    val abortedAtBoundary: Boolean = false
) {
    /** 是否发生过回退补证。 */
    val hadFallback: Boolean get() = arbitrateRevisions > 0

    /** 是否产出了可用结论。被中止的运行不产出结论，哪怕 [status] 看起来是终态。 */
    val conclusive: Boolean get() = !abortedAtBoundary
}

/**
 * 五节点状态图编排器。
 *
 * 它只做四件事，一件都不多：
 *
 * 1. **按前向顺序推进**，并在回退边出现时把计数交给节点自己判断额度。
 * 2. **推进时传对 attemptNo**。这个值是仲裁节点判断「还剩几次回退额度」的唯一依据，
 *    传错会让回退额度失效，进而形成活锁。
 * 3. **在终态出现时立即停止**，不再执行后续节点。`next` 与 `terminalStatus` 同时非空时
 *    以 `terminalStatus` 为准——节点直接判定终态意味着后续节点没有必要执行。
 * 4. **留痕**。每一步的进入/离开原因与回退触发都记下来，否则
 *    「这一步为什么被路由到这里」在事后无法回答。
 *
 * 另有一处**不是**编排职责的职责：[RcaStateGraph.run] 里的节点边界钩子。
 * 编排器不判断租约，它只是承认「只有节点边界可以安全中断」这个事实，
 * 并把控制权交给调用方。这样做而不是把租约塞进节点，是因为租约属于调度层：
 * 节点必须能脱离数据库单独复算（否则固定回放就退化成端到端集成测试）。
 * 反过来，让调用方自己在外面循环调用节点也不行——路由规则会因此存在两份实现。
 *
 * 编排器**不做业务判断**：强制证据是否齐、反证是否排除、引用是否撑得住结论，
 * 分别由 BASELINE_EVIDENCE、StopEvaluator、ROOT_CAUSE_ARBITRATE 决定。
 * 编排器只按它们的结论路由，这样每个判断都只有一个归属地。
 */
class RcaStateGraph(
    handlers: List<RcaNodeHandler>,
    /** 硬上限。存在的意义是防御性的：任何一条未预期的环都会在这里被切断。 */
    private val maxTransitions: Int = MAX_TRANSITIONS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val byNode: Map<RcaNode, RcaNodeHandler> = handlers.associateBy(RcaNodeHandler::node)

    init {
        // 缺节点属于装配错误，必须在启动时就炸掉，而不是等到某个节点被路由到时才空指针。
        val missing = RcaNode.FORWARD_ORDER.filterNot(byNode::containsKey)
        require(missing.isEmpty()) { "状态图缺少节点实现：${missing.joinToString { it.storageCode }}" }
        require(handlers.size == byNode.size) { "同一节点被注册了多个实现，路由将不确定" }
        require(maxTransitions >= MIN_REQUIRED_TRANSITIONS) {
            "硬上限 $maxTransitions 小于最坏合法路径所需的 $MIN_REQUIRED_TRANSITIONS 步，会把正常回退误判为超限"
        }
    }

    /**
     * 从 [startNode] 开始跑到终态。
     *
     * [startNode] 由调用方给出而不是从状态里读：当前节点属于**任务调度状态**
     * （落库在 `t_rca_agent_task.current_node`），不属于共享状态。恢复时调度器
     * 读出它再调用本方法，这样共享状态本身保持纯业务语义、不含调度信息。
     *
     * [traceId] 同理属于调度信息（它来自任务行，见 `RcaClaimedTask.traceId`），
     * 编排器只把它原样交给节点，**不参与路由**：编排器在这里的判断依旧只有
     * 「下一个是谁」「该不该停」两件，链路标识不改变任何一个。
     */
    fun run(
        state: RcaSharedState,
        startNode: RcaNode = RcaNode.CONTEXT_BUILD,
        /**
         * 节点边界钩子：**即将进入** [RcaNode] 时被调用，返回 false 表示调用方要求中止本次运行。
         *
         * 传入的参数是「下一个要进入的节点」与「进入它之前的完整状态」——
         * 这正好是恢复所需的一对信息：把这份状态连同这个节点落库，
         * 就等于记下了「从哪继续」。钩子在**进入节点之前**触发而不是之后，
         * 是因为进入之后状态已经变了，那时落库记下的是「刚做完的节点」，
         * 恢复时会被重新执行一遍，而某些节点并不幂等（例如已计入预算的调用）。
         *
         * 这不是「通用的生命周期回调」：目前唯一的用途是租约续期与状态落库，
         * 因此它没有额外参数、没有返回值语义的扩展空间。要加新的回调请另开一个钩子，
         * 不要让这一个承担两种时序。
         */
        onNodeBoundary: (RcaNode, RcaSharedState) -> Boolean = { _, _ -> true },
        /**
         * 链路标识，进入每个节点时原样交给它（见 [RcaNodeContext.traceId]）。
         *
         * 默认 null 而不是「没有就生成一个」：生成的责任在领取侧（`claim` 语句），
         * 编排器凭空造一个会让「库里那一个」与「节点手里那一个」不一致——
         * 而链路标识唯一要保证的就是同一件事在所有地方是同一个值。
         */
        traceId: String? = null
    ): RcaRunResult {
        val visits = mutableMapOf<RcaNode, Int>()
        val steps = mutableListOf<RcaStepTrace>()
        var current = startNode
        var currentState = state
        var revisions = 0

        repeat(maxTransitions) {
            if (!onNodeBoundary(current, currentState)) {
                return RcaRunResult(
                    status = RcaTaskStatus.CANCELLED,
                    stopReason = RcaStopReason.CANCELLED,
                    finalState = currentState,
                    steps = steps,
                    nodeVisits = visits,
                    arbitrateRevisions = revisions,
                    abortedAtBoundary = true
                )
            }

            val handler = byNode.getValue(current)
            val attemptNo = visits.getOrDefault(current, 0)
            visits[current] = attemptNo + 1

            val startedAt = clock()
            val outcome = handler.execute(RcaNodeContext(currentState, attemptNo, traceId))
            val elapsedMs = clock() - startedAt

            val fallback = outcome.fallback
            if (fallback != null) revisions += 1

            steps += RcaStepTrace(
                node = current,
                attemptNo = attemptNo,
                enterReason = outcome.enterReason,
                leaveReason = outcome.leaveReason,
                fallbackTrigger = fallback?.trigger,
                stateVersionBefore = currentState.stateVersion,
                stateVersionAfter = outcome.state.stateVersion,
                elapsedMs = elapsedMs
            )
            currentState = outcome.state

            // 终态优先于 next：节点直接判定终态时，后续节点没有必要执行。
            outcome.terminalStatus?.let { terminal ->
                return RcaRunResult(
                    status = terminal,
                    stopReason = outcome.stopReason ?: RcaStopReason.HUMAN_REQUIRED,
                    finalState = currentState,
                    steps = steps,
                    nodeVisits = visits,
                    arbitrateRevisions = revisions
                )
            }

            current = outcome.next
                ?: return RcaRunResult(
                    status = RcaTaskStatus.FAILED,
                    stopReason = RcaStopReason.NO_VIABLE_TOOL,
                    finalState = currentState,
                    steps = steps,
                    nodeVisits = visits,
                    arbitrateRevisions = revisions
                )
        }

        // 走到这里说明硬上限被触发，属于装配缺陷或未预期的环，按转人工收口而不是静默截断。
        return RcaRunResult(
            status = RcaTaskStatus.NEEDS_HUMAN,
            stopReason = RcaStopReason.HUMAN_REQUIRED,
            finalState = currentState,
            steps = steps,
            nodeVisits = visits,
            arbitrateRevisions = revisions
        )
    }

    private companion object {
        /**
         * 最坏合法路径的长度上界。
         *
         * 推导：五个前向节点各走一次（5 步），每发生一次回退会**额外**多走
         * 「仲裁 → 调查」两步，回退最多 [RcaFallbackEdge.MAX_ARBITRATE_REVISIONS] 次。
         * 这是下界而不是经验值，所以 [maxTransitions] 低于它必须直接拒绝装配——
         * 否则正常回退会被硬上限截断成转人工，而且现象很难归因到「上限配小了」。
         */
        const val MIN_REQUIRED_TRANSITIONS = 5 + RcaFallbackEdge.MAX_ARBITRATE_REVISIONS * 2

        /** 默认硬上限取最坏合法路径的两倍，留出余量。 */
        const val MAX_TRANSITIONS = MIN_REQUIRED_TRANSITIONS * 2
    }
}
