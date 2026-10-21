package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.ToolCallRecord

/**
 * 已落库的工具调用记录。
 *
 * 这是 [RcaToolRecovery] 判定的**输入来源**，也是它与共享状态快照之间的分工：
 * 快照回答「证据在不在账本里」，调用记录回答「这次调用走到了哪一步」。
 * 两者缺一不可——只有快照就看不到「已发出但未落定」，只有记录就看不到「证据没进账本」。
 *
 * 实现对应 `t_rca_tool_call`（`idx_rca_tool_dedup` 覆盖 `task_id` 前缀，
 * 按任务取记录是一次索引扫描，可以放在每次领取时做）。
 *
 * [EMPTY] 是给「这套部署还没有接记录表」用的：它让恢复判定退化成「无可判定」，
 * 而不是让 Worker 因为缺一个端口就起不来。退化的方向必须是**不做假设**——
 * 空记录表意味着「没有已知的在途调用」，而不是「调用都成功了」。
 */
fun interface RcaToolCallJournalPort {

    /** 该任务已落库的调用记录，按 `id` 升序。 */
    fun recordsOf(taskId: Long): List<ToolCallRecord>

    companion object {
        val EMPTY: RcaToolCallJournalPort = RcaToolCallJournalPort { emptyList() }
    }
}

/**
 * 恢复判定对本次执行的结论。
 *
 * 只有两种结果，没有「继续但记一笔」这种中间态：`未能判定` 的调用一旦被放行，
 * 它就会在后续的结论里以「已查过」的面目出现，而没有任何地方能再纠正它。
 */
sealed interface RcaRecoveryOutcome {

    /** 所有在途调用都判定清楚了（复用、对账后重放），可以继续跑图。 */
    data object Proceed : RcaRecoveryOutcome

    /** 存在无法判定的调用，整体转人工。 */
    data class Blocked(val action: RcaRecoveryAction, val reason: String) : RcaRecoveryOutcome
}

/**
 * 领取任务后的恢复判定入口。
 *
 * 它把 [RcaToolRecovery] 的判定接到 Worker 的生命周期上——**没有这一步，
 * 三态对账就只是一段没人调用的规则**：Worker 拿到快照直接就往下跑，
 * 那条「已发出但上游可能已经执行」的记录既不会被对账，也不会拦住它。
 *
 * ## 为什么是「一条不过就整体拦下」
 *
 * 逐条判定，但处置是整体的：只要有一条记录无法判定，本次运行就不开始。
 * 跳过那一条继续跑看起来更「可用」，实际是把一个已知不可判定的调用
 * 变成了后续结论里的既成事实——结论会引用它、预算会算上它，
 * 而它究竟执行没执行永远没人知道。恢复的语义是「要么回到崩溃前的样子，
 * 要么停」，不是「尽量往前挪一点」。
 *
 * ## 判定为「重放」时为什么不在这里重放
 *
 * 这里只做**拦截**，不做执行。重放的位置在节点内部（守卫查不到可复用记录时自然重新调用），
 * 在那里重放才能拿到正确的决策轮次、预算扣减与证据入账。
 * 如果在这里先补一次调用，节点随后还会再发一次——两次调用、一条证据，
 * 而两条日志都指向同一次上游请求，事后无法分辨。
 */
class RcaRecoveryResolver(
    private val journal: RcaToolCallJournalPort,
    private val provider: ProviderStateQueryPort
) {

    /** 判定该任务当前是否可以继续执行。 */
    fun resolve(taskId: Long, state: RcaSharedState): RcaRecoveryOutcome {
        journal.recordsOf(taskId).forEach { call ->
            val first = RcaToolRecovery.decide(call, state)
            // 需要问上游时才去问：对账是一次带网络代价的调用，
            // 不能在本地已经有结论的记录上白白花掉。
            val settled = if (first.requiresProviderQuery) {
                val requestId = call.providerRequestId
                    ?: return RcaRecoveryOutcome.Blocked(
                        action = RcaRecoveryAction.REQUIRE_HUMAN,
                        reason = "调用 ${call.toolName} 判定为需要对账，却没有上游请求 ID，无法提问"
                    )
                RcaToolRecovery.afterReconcile(call, provider.queryFinalState(requestId), state)
            } else {
                first
            }
            if (!settled.safeToContinue) {
                return RcaRecoveryOutcome.Blocked(settled.action, settled.reason)
            }
        }
        return RcaRecoveryOutcome.Proceed
    }

    companion object {

        /**
         * 没有记录表、也没有对账能力时的实现：一律放行。
         *
         * 它**不是**「默认安全」，而是「这一层没有可用的信息」——
         * 两种实现（空记录表 / 有记录表但查不到上游）必须分开：
         * 前者的语义是「没有已知的在途调用」，后者是「有一批调用无法判定」，
         * 后者会走到 [RcaRecoveryOutcome.Blocked]。把两者合一会让一个没接记录表的部署
         * 在每次领取时都把任务判成转人工。
         */
        val NONE: RcaRecoveryResolver = RcaRecoveryResolver(
            journal = RcaToolCallJournalPort.EMPTY,
            provider = ProviderStateQueryPort { ProviderFinalState.UNREACHABLE }
        )
    }
}
