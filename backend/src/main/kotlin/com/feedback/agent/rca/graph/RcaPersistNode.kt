package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.RcaArbitrationOutcome
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus

/**
 * 持久化端口。
 *
 * **所有写方法都必须幂等**，键取确定性标识而不是自增主键：
 * 证据用 `(task_id, evidence_id)`（evidence ID 由工具与参数摘要推导，天然稳定），
 * 假设用 `(task_id, hypothesis_code, revision)`，归因用 `(episode_id, revision)`。
 * 这么要求的原因是持久化节点会重放——任务在「归因已写库、任务行尚未置终态」这个窗口崩溃后
 * 恢复，会从头再跑一遍持久化节点。没有幂等性就会写出两条归因，而
 * `t_feedback_episode_attribution` 的追加式设计会让这种重复很难被发现。
 */
interface RcaPersistencePort {

    /** 写证据账本。必须在归因之前完成——归因引用的 evidence ID 要能回源到行。 */
    fun saveEvidence(taskId: Long, evidence: List<EvidenceEntry>)

    /** 写假设及其支持/反驳证据的引用关系。 */
    fun saveHypotheses(taskId: Long, hypotheses: List<Hypothesis>)

    /** 写强制证据计划的逐项完成情况，含失败语义与豁免原因。 */
    fun saveMandatoryEvidence(taskId: Long, requirements: List<MandatoryRequirement>)

    /** 写归因与逐对引用校验结果，返回归因行主键。 */
    fun saveArbitration(outcome: RcaArbitrationOutcome): Long

    /**
     * 关联 Incident，返回 Incident 主键。
     *
     * 只在 [RcaArbitrationOutcome.autoLinkPermitted] 为 true 时调用，
     * 但实现侧仍必须**独立再判一次**等级——调用方判断是业务约束，
     * 数据库的 `chk_episode_attribution_autolink` 是最后一道防线，两者都要在。
     */
    fun linkIncident(attributionRowId: Long, outcome: RcaArbitrationOutcome): Long

    /**
     * 写知识候选。返回 null 表示本次结论不够格入库。
     *
     * 「不够格」是一个正常结果而不是错误：只有可执行归因（前两档）才沉淀为知识，
     * 歧义与未归因的结论入库会污染后续检索。
     */
    fun saveKnowledgeCandidate(request: KnowledgeCandidateRequest): Long?
}

/** 知识候选的写入请求。 */
data class KnowledgeCandidateRequest(
    val taskId: Long,
    val episodeId: Long,
    val signatureCanonical: String,
    val attributionLevelCode: String,
    val evidenceIds: List<String>,
    val summary: String
)

/**
 * RCA_PERSIST：纯确定性节点，把仲裁产出落库。此节点不产生新的调查动作。
 *
 * 落库顺序是有依赖的，不能随便调换：
 * 证据 → 假设 → 强制证据 → 归因 → Incident 关联 → 知识候选。
 *
 * 原因是每一步都引用前一步的行：归因的 `basis_evidence_ids` 必须能回源到已写入的证据行；
 * Incident 关联要更新归因行的 `incident_id`，所以必须在归因之后；
 * 知识候选引用归因等级与证据集合，所以最后。
 *
 * 本节点**不写任务行本身**（状态、租约、state_version）。任务生命周期归编排器管，
 * 因为「谁持有租约、当前 state_version 是多少」是编排器的职责，
 * 放在节点里会让节点必须知道自己的调度上下文，破坏「节点可单独复算」这条约束。
 */
class RcaPersistNode(
    private val port: RcaPersistencePort
) : RcaNodeHandler {

    override val node: RcaNode = RcaNode.RCA_PERSIST

    override fun execute(context: RcaNodeContext): RcaNodeOutcome {
        val state = context.state
        val arbitration = state.arbitration
            ?: return inconsistent(
                state,
                "到达持久化节点但共享状态里没有仲裁产出，属状态不一致，转人工而不是写出空结论"
            )

        port.saveEvidence(state.taskId, state.evidenceLedger)
        port.saveHypotheses(state.taskId, state.hypotheses)
        port.saveMandatoryEvidence(state.taskId, state.mandatoryPlan.requirements)

        val attributionRowId = port.saveArbitration(arbitration)

        // 自动关联 Incident 的权限由归因等级决定，不在这里重写规则。
        val incidentId = if (arbitration.autoLinkPermitted) {
            port.linkIncident(attributionRowId, arbitration)
        } else {
            null
        }

        val candidateId = port.saveKnowledgeCandidate(
            KnowledgeCandidateRequest(
                taskId = state.taskId,
                episodeId = state.episodeId,
                signatureCanonical = arbitration.attribution.signature.canonical,
                attributionLevelCode = arbitration.attribution.level.storageCode,
                evidenceIds = arbitration.attribution.basisEvidenceIds,
                summary = knowledgeSummary(state, arbitration)
            )
        )

        return RcaNodeOutcome(
            state = state.copy(stateVersion = state.stateVersion + 1),
            next = null,
            enterReason = "仲裁完成后持久化归因、证据账本与知识候选",
            leaveReason = buildString {
                append("已写入证据 ${state.evidenceLedger.size} 条、假设 ${state.hypotheses.size} 个、")
                append("强制证据 ${state.mandatoryPlan.requirements.size} 项、归因行 $attributionRowId")
                append(
                    if (incidentId != null) {
                        "，自动关联 Incident $incidentId"
                    } else {
                        "，未自动关联 Incident（归因等级 ${arbitration.attribution.level.storageCode}）"
                    }
                )
                append(if (candidateId != null) "，知识候选 $candidateId" else "，未沉淀知识候选（结论不可执行）")
            },
            terminalStatus = arbitration.finalStatus,
            stopReason = arbitration.stopReason
        )
    }

    /**
     * 知识候选摘要。
     *
     * 只拼稳定字段与等级，不掺入影响面与时间——知识检索的键必须与
     * [com.feedback.agent.rca.domain.RootCauseSignature] 保持一致，
     * 掺入动态特征会让同一条经验分裂成多条。
     */
    private fun knowledgeSummary(state: RcaSharedState, arbitration: RcaArbitrationOutcome): String = buildString {
        append("组件 ${arbitration.attribution.signature.component}、")
        append("故障类型 ${arbitration.attribution.signature.faultType}、")
        append("错误签名 ${arbitration.attribution.signature.errorSignature}；")
        append("现象：${state.problemProfile.symptomSummary}")
    }

    private fun inconsistent(state: RcaSharedState, explanation: String): RcaNodeOutcome = RcaNodeOutcome(
        state = state,
        next = null,
        enterReason = "仲裁完成后持久化归因",
        leaveReason = explanation,
        terminalStatus = RcaTaskStatus.NEEDS_HUMAN,
        stopReason = RcaStopReason.HUMAN_REQUIRED
    )
}
