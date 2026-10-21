package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.ToolCallRecord

/**
 * 恢复时对一条工具调用记录的处置动作。
 *
 * 四个动作覆盖了「记录 + 上游」两处的全部组合，没有第五种：一条记录要么已经有可用的结果
 * （复用），要么能问清上游（对账），要么问不清但能安全重来（重放），要么三者都不成立（转人工）。
 * 关键在于**不能把「问不清」当成「没有」**：那正是把「查不到」写成「没问题」的入口。
 */
enum class RcaRecoveryAction(val storageCode: String) {

    /** 复用已记录的结论，不再碰上游，也不再计费。 */
    REUSE_LOCAL_OBSERVATION("REUSE_LOCAL_OBSERVATION"),

    /** 向上游按 `provider_request_id` 询问这次调用的最终状态。 */
    RECONCILE_WITH_PROVIDER("RECONCILE_WITH_PROVIDER"),

    /** 重新执行一次只读调用。 */
    REPLAY_READ_ONLY("REPLAY_READ_ONLY"),

    /** 无法判定，转人工。 */
    REQUIRE_HUMAN("REQUIRE_HUMAN")
}

/**
 * 上游对「这次调用的最终状态」的回答。
 *
 * 四态而不是三态：[NOT_SUPPORTED] 与 [UNREACHABLE] 必须分开——
 * 前者是上游明确表态「我不提供这种对账能力」，是稳定的能力缺口，可以按既定策略重放；
 * 后者是「这次问不到」，是暂时性的未知，不能据此认为上游没执行过。
 * 合成一态会让能力缺口和网络抖动走进同一条分支。
 */
enum class ProviderFinalState(val storageCode: String) {

    /** 上游确认这次调用已经执行完成。 */
    FINAL_SUCCESS("FINAL_SUCCESS"),

    /** 上游确认这次调用已经以失败告终。 */
    FINAL_FAILURE("FINAL_FAILURE"),

    /** 上游明确不支持按请求 ID 对账。 */
    NOT_SUPPORTED("NOT_SUPPORTED"),

    /** 上游查不到（超时、路由不到、ID 已过期）。**未知，不是「没执行过」。** */
    UNREACHABLE("UNREACHABLE")
}

/**
 * 上游状态查询端口。
 *
 * 只回答「最终状态」，不回答「正文是什么」——正文由工具自己按上游响应构造，
 * 不存在「按请求 ID 取回 Observation」的通用接口。这个限制决定了下面的结论：
 * 对账的价值不在于把结果取回来，而在于把「不可判定」这一种情形单独摘出来。
 */
fun interface ProviderStateQueryPort {

    fun queryFinalState(providerRequestId: String): ProviderFinalState
}

/**
 * 一条恢复决策。
 *
 * [reason] 是**给人看的判定依据**，必须能回答「为什么是这条路」：
 * 同一类事故在两台 Worker 上得到不同处置时，只有它能说明差异从哪来。
 */
data class RcaRecoveryDecision(
    val action: RcaRecoveryAction,
    val reason: String
) {
    val requiresProviderQuery: Boolean get() = action == RcaRecoveryAction.RECONCILE_WITH_PROVIDER
    val safeToContinue: Boolean get() = action != RcaRecoveryAction.REQUIRE_HUMAN
}

/**
 * 工具调用的三态恢复判定。
 *
 * ## 判据是「记录 + 状态」两个来源的比对，不是记录自己
 *
 * 恢复时能依靠的事实有两个，缺一个就会做出错误处置：
 * - **持久化的调用记录**（`t_rca_tool_call`）：这次调用在本地记到了哪一步。
 * - **状态快照里的证据账本**：正文有没有真的进过账。
 *
 * | 记录 | 状态 | 动作 |
 * | --- | --- | --- |
 * | `COMMITTED` + 有摘要，证据在账本里 | 已一致 | 复用 |
 * | `COMMITTED` + 有摘要，证据不在账本里 | 半提交 | 重放（补齐证据） |
 * | `COMMITTED` + 无摘要（缺口语义） | 已一致 | 复用（按缺口复用） |
 * | `PENDING` + 有请求 ID | 未落定 | 对账 |
 * | `PENDING` + 无请求 ID | 未落定且无从对账 | 预算允许则重放 |
 * | `UNKNOWN` | 连请求是否发出都不可知 | 转人工 |
 *
 * 「半提交」这一行最容易被漏掉，也最危险：调用记录说这次查询成功了，但账本里没有它的证据。
 * 直接复用会让调查继续往前跑，而它引用的那条证据**不存在**——
 * 结论看起来证据充分，回源时才发现引用是断的。所以这种情况下必须重放，
 * 让确定性 evidence ID 把那条证据补回账本。
 *
 * `UNKNOWN` 之所以不能重放：本地账目丢了一个它本该记住的事实（请求是否发出）。
 * 在这条基础上继续跑，产出的结论会带着一条无法追溯来源的证据，
 * 而「可回源」是这套设计对外承诺的第一件事。失败关闭比猜一个更划算。
 *
 * ## 为什么对账之后仍然要重放
 *
 * 对账只回答「上游成没成」，回答不了「正文是什么」。请求 ID 对应的响应正文要么在崩溃里丢了，
 * 要么上游根本不提供回取接口。所以除 [ProviderFinalState.UNREACHABLE] 之外，
 * 对账的结论都是「可以安全重放」；它真正拦下来的是「问不到」那一种。
 *
 * 重放**不会**造成重复证据与重复扣费，两条理由都是结构性的，不是靠自觉：
 * - evidence ID 由 `工具 + 参数摘要` 确定性生成（见 `tools.EvidenceIdFactory`），
 *   重放得到的是同一个 ID，账本里不会多出一条；
 * - 第一次调用的结果从未提交，因此它的预算扣减也从未落库，重放的那一次才是首次扣减。
 *
 * ## 重放的前提
 *
 * 只读。这一点目前由类型系统保证：`RcaToolInvoker` 没有写方法，
 * 七类工具按契约都不可修改业务数据。**新增任何带副作用的工具前必须先改这里**——
 * 让一个会改数据的工具走进重放分支，等于把「恢复」变成「重复执行」。
 */
object RcaToolRecovery {

    /** 第一步：只看本地记录与状态快照，决定是复用、对账、重放还是转人工。 */
    fun decide(call: ToolCallRecord, state: RcaSharedState): RcaRecoveryDecision = when (call.localCommitState) {
        LocalCommitState.COMMITTED -> decideCommitted(call, state)
        LocalCommitState.PENDING -> decidePending(call, state)
        LocalCommitState.UNKNOWN -> RcaRecoveryDecision(
            action = RcaRecoveryAction.REQUIRE_HUMAN,
            reason = "调用 ${call.reference()} 的本地记账为 UNKNOWN，" +
                "无法断言请求是否发出；未知不得当成空结果或失败"
        )
    }

    /**
     * 第二步：拿到上游的回答之后再落定处置。
     *
     * 只有 [RcaRecoveryAction.RECONCILE_WITH_PROVIDER] 需要走这一步。
     * 上游说不出最终状态时**不得**回退到重放：那等于把「不知道」当成「没执行过」。
     */
    fun afterReconcile(
        call: ToolCallRecord,
        provider: ProviderFinalState,
        state: RcaSharedState
    ): RcaRecoveryDecision = when (provider) {
        ProviderFinalState.UNREACHABLE -> RcaRecoveryDecision(
            action = RcaRecoveryAction.REQUIRE_HUMAN,
            reason = "上游查不到 ${call.reference()} 的请求 ${call.providerRequestId} 的最终状态，" +
                "无法判定是否已执行；转人工核对，不得按未执行处理"
        )
        // 三种「上游答得上话」的情形走同一条路：正文都不在本地，
        // 而重放是唯一能拿到正文的途径，且不会造成重复证据或重复扣费（见类注释）。
        ProviderFinalState.FINAL_SUCCESS,
        ProviderFinalState.FINAL_FAILURE,
        ProviderFinalState.NOT_SUPPORTED -> replayDecision(call, state, provider)
    }

    /**
     * 已落定的记录：先看它有没有把证据带进账本。
     *
     * 复用条件与 `tools.ToolInvocationGuard` 的查重条件保持**同一条**
     * （工具 + 参数摘要 + 已提交 + 摘要与摘要正文都有值）。两处规则若不一致，
     * 会出现「恢复时判定可复用、真正查重时又查不到」的裂缝，于是同一次调用被查两遍。
     */
    private fun decideCommitted(call: ToolCallRecord, state: RcaSharedState): RcaRecoveryDecision {
        // 声称取到了数据却没有 Observation 摘要，属自相矛盾：既不能复用（没有东西可复用），
        // 也不能重放（重放会掩盖这条损坏记录）。它说明写入路径本身有问题，必须让人看见。
        if (call.status.hasData && call.observationDigest == null) {
            return RcaRecoveryDecision(
                action = RcaRecoveryAction.REQUIRE_HUMAN,
                reason = "调用 ${call.reference()} 声明状态 ${call.status.storageCode} 却没有 Observation 摘要，" +
                    "记录自相矛盾，不能复用也不能重放"
            )
        }
        // 没有观察结果：这是一次已落定的失败或缺口。缺口语义本身就是结论，
        // 按原语义复用即可——把缺口重放成成功，等于抹掉「这次没查成」这个事实。
        if (call.observationDigest == null) {
            return RcaRecoveryDecision(
                action = RcaRecoveryAction.REUSE_LOCAL_OBSERVATION,
                reason = "调用 ${call.reference()} 已以 ${call.status.storageCode} 落定且无观察结果，" +
                    "缺口语义同样属于已提交的结论，按原语义复用"
            )
        }
        val missing = call.evidenceIds.filter { state.evidenceById(it) == null }
        return if (missing.isEmpty()) {
            RcaRecoveryDecision(
                action = RcaRecoveryAction.REUSE_LOCAL_OBSERVATION,
                reason = "调用 ${call.reference()} 的 Observation 已提交（摘要 " +
                    "${call.observationDigest.take(DIGEST_PREFIX_LENGTH)}）且证据已在账本，" +
                    "直接复用，不重复取证、不重复计费"
            )
        } else {
            RcaRecoveryDecision(
                action = RcaRecoveryAction.REPLAY_READ_ONLY,
                reason = "调用 ${call.reference()} 已提交但证据 ${missing.joinToString(",")} 不在账本里（半提交），" +
                    "复用会让后续引用指向不存在的证据；按确定性摘要重放以补齐账本"
            )
        }
    }

    /** 已发出但未落定的调用：有凭据就对账，没凭据只能重放。 */
    private fun decidePending(call: ToolCallRecord, state: RcaSharedState): RcaRecoveryDecision =
        if (call.providerRequestId != null) {
            RcaRecoveryDecision(
                action = RcaRecoveryAction.RECONCILE_WITH_PROVIDER,
                reason = "调用 ${call.reference()} 已带请求 ID ${call.providerRequestId} 发出但未落定，" +
                    "先向上游对账再决定"
            )
        } else {
            replayDecision(call, state, null)
        }

    /**
     * 重放判定：只读 + 预算允许。
     *
     * 预算不足时不重放也不硬造结论，而是转人工。理由是此时有两件都可能的事同时成立：
     * 任务本来可能已该按预算收口（那是节点的缺口策略该做的判断），
     * 或者恢复信息不足以重建这次调用。两条路都不允许把这次调用记成「查了没有」。
     */
    private fun replayDecision(
        call: ToolCallRecord,
        state: RcaSharedState,
        provider: ProviderFinalState?
    ): RcaRecoveryDecision {
        state.gapsAndBudget.exhaustionStopReason()?.let { reason ->
            return RcaRecoveryDecision(
                action = RcaRecoveryAction.REQUIRE_HUMAN,
                reason = "调用 ${call.reference()} 需要重放，但预算已耗尽（${reason.storageCode}），" +
                    "不能重放也不能当作已查"
            )
        }
        val source = provider?.let { "上游对账结论 ${it.storageCode}" } ?: "无对账凭据"
        return RcaRecoveryDecision(
            action = RcaRecoveryAction.REPLAY_READ_ONLY,
            reason = "调用 ${call.reference()} 未落定（$source）；只读工具按确定性参数摘要重放，" +
                "evidence ID 不变，不产生重复证据与重复扣减"
        )
    }

    /** 记录的可读指代：工具名 + 参数摘要前缀，够定位到唯一一条记录，又不至于把摘要抄进日志。 */
    private fun ToolCallRecord.reference(): String =
        "$toolName#${paramsDigest.take(DIGEST_PREFIX_LENGTH)}"

    private const val DIGEST_PREFIX_LENGTH = 16
}
