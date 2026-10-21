package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.MandatoryEvidencePlan
import com.feedback.agent.rca.domain.ProblemProfile
import com.feedback.agent.rca.domain.StopProposal
import com.feedback.agent.rca.domain.ToolRequest
import java.math.BigDecimal

/**
 * 交给模型的调查上下文。
 *
 * 这里**不放原文**：日志正文、堆栈正文、变更明细都不进上下文，只有结构化摘要与 evidence ID。
 * 这是「日志不是上下文，结论才是」在类型层面的落地——[EvidenceBrief] 里根本没有承载原文的字段，
 * 想放也放不进去。需要原文时人工按 evidence ID 回源，而不是让模型先读一遍再复述。
 *
 * 同理，[hypotheses] 只给编码、陈述、置信等级和证据 ID 列表，不给推理过程。
 * 模型的推理过程对下一步决策没有增量价值，只会持续占用上下文。
 */
data class InvestigationPrompt(
    val problemProfile: ProblemProfile,
    val mandatoryPlan: MandatoryEvidencePlan,
    val hypotheses: List<Hypothesis>,
    val evidenceBriefs: List<EvidenceBrief>,
    val gaps: List<String>,
    val budget: GapsAndBudget,
    /** 第几轮决策，从 1 开始。 */
    val round: Int,
    /** 前几轮已经用过的工具与参数摘要，用于让模型避免重复取证。 */
    val attemptedReuseKeys: List<String>
)

/**
 * 给模型看的证据摘要。只有摘要、类型、覆盖范围和覆盖口径，没有正文。
 * [coverageType] 必须给模型看：它决定了这条证据能不能支撑范围性结论。
 */
data class EvidenceBrief(
    val evidenceId: String,
    val type: String,
    val coverageType: String,
    val coverageScope: String,
    val summary: String,
    /** 是否有分母与对照组；模型据此判断能否用于范围性 claim。 */
    val cohortComplete: Boolean
) {
    companion object {
        fun of(entry: EvidenceEntry): EvidenceBrief = EvidenceBrief(
            evidenceId = entry.evidenceId,
            type = entry.type.storageCode,
            coverageType = entry.coverageType.storageCode,
            coverageScope = entry.coverageScope,
            summary = entry.summary,
            cohortComplete = entry.cohortComplete
        )
    }
}

/**
 * 模型的一步决策。
 *
 * 两种形态互斥：要么提交一次调查动作（[investigate]），要么提交停止提案（[stop]）。
 * 不允许「同时给结论又给下一步」——那会让编排器无法判断到底该停还是该继续，
 * 而这类歧义在线上表现为「有时停有时不停」，极难定位。
 */
data class AgentDecision(
    val investigate: InvestigateAction? = null,
    val stop: StopProposal? = null,
    /** 模型自述的选工具理由，仅进审计，不参与判定。 */
    val reasoning: String,
    /** 本轮新提出或修正的假设；可以为空（只做取证不修假设）。 */
    val hypothesisUpdates: List<HypothesisUpdate> = emptyList(),
    val modelVersion: String = "",
    val promptVersion: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: BigDecimal = BigDecimal.ZERO
) {
    init {
        require((investigate == null) != (stop == null)) {
            "一步决策必须且只能二选一：提交调查动作或提交停止提案"
        }
    }

    /** 本次决策的预算消耗，用于累加进共享状态。 */
    val tokenCost: Int get() = inputTokens + outputTokens
}

/** 一次调查动作：检验哪个假设、用什么工具、带什么参数。 */
data class InvestigateAction(
    /**
     * 本次调用要检验的假设编码；必须落在调用参数里，因此会进参数摘要，避免不同假设共用去重键。
     *
     * 为空表示**探索性取证**：这次调用是为了看清现象，不是为了检验某个假设。
     * 这个语义必须显式存在，不能让调用方随便填一个假设顶上。回填规则是
     * 「取到数据 → 计入被检验假设的支持证据」，所以顶上来的假设会凭空获得支持：
     * 查服务健康得到「整体正常」，若声明它检验「服务整体故障」，账本就会记成
     * 这条证据**支持**了服务故障。允许为空是这套记账不出错的前提。
     */
    val testsHypothesis: String? = null,
    val toolRequest: ToolRequest
)

/** 假设的提出、修正、合并与丢弃。 */
data class HypothesisUpdate(
    val code: String,
    val statement: String,
    val action: HypothesisUpdateAction,
    val mechanismNote: String? = null,
    /** 合并目标：同一批证据且机制链不可区分时合并，被合并方保留原记录。 */
    val mergedIntoCode: String? = null
)

enum class HypothesisUpdateAction {
    /** 新提出。 */
    PROPOSE,

    /** 修正陈述或机制链，revision 递增。 */
    REVISE,

    /** 与另一假设合并。 */
    MERGE,

    /** 证据已明确排除，丢弃但保留记录。 */
    DROP
}

/**
 * 调查模型端口。
 *
 * 把它抽成端口有两个直接收益：
 * 1. 固定回放夹具可以实现同一接口，从而在**不调用真实模型**的前提下复算整条状态图，
 *    这样「路径改变了工具选择」这件事才能被断言，而不是靠读日志判断。
 * 2. 模型版本与 Prompt 版本是接口的一部分，必须随每次决策回传并落库；
 *    否则事后无法回答「这次结论是哪个模型、哪版提示词产出的」。
 */
interface InvestigationModel {

    /** 模型快照标识，例如 gpt-5.4-medium。落库到任务与工具调用记录。 */
    val modelVersion: String

    /** 提示词版本，与 prompts 资产目录下的版本号一致。 */
    val promptVersion: String

    /**
     * 基于调查上下文做出下一步决策。
     *
     * 实现方不得抛出异常表达「模型不可用」：应返回一个带停止提案的决策并给出
     * [com.feedback.agent.rca.domain.RcaStopReason.HUMAN_REQUIRED]，
     * 让编排器走正常的降级路径，而不是让异常穿透。
     */
    fun decide(prompt: InvestigationPrompt): AgentDecision
}
