package com.feedback.agent.rca.replay

import com.feedback.agent.rca.domain.EpisodeAttributionLevel
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.RcaArbitrationOutcome
import com.feedback.agent.rca.graph.KnowledgeCandidateRequest
import com.feedback.agent.rca.graph.RcaPersistencePort

/**
 * 回放用的持久化端口：只记账，不落库。
 *
 * 它替掉的是**数据库**，不是持久化契约。写入键与生产实现保持一致：
 * 证据按 `(taskId, evidenceId)`、假设按 `(taskId, code, revision)`、
 * 归因按 `(episodeId, revision)`、强制证据按 `(taskId, code)`，并且按同一批键去重。
 *
 * 之所以要在这里实现去重而不是简单地往 List 里塞：持久化节点会重放。任务在
 * 「归因已写库、任务行尚未置终态」这个窗口崩溃后恢复，会从头再跑一遍该节点。
 * 没有幂等性就会写出两条归因，而 `t_feedback_episode_attribution` 是追加式设计，
 * 重复行很难在事后被发现。回放因此必须能验证这条契约——它无法靠人工 review 确认。
 *
 * 另外两处「实现侧独立再判一次」的约束也照做：自动关联 Incident 要求可执行归因，
 * 知识候选只接受可执行结论。生产实现的这两条分别由数据库 CHECK 约束与写入前校验承担，
 * 调用方判断属于业务层，不能替代这两道防线。
 */
class InMemoryRcaPersistencePort : RcaPersistencePort {

    private val evidenceRows: MutableMap<String, EvidenceEntry> = linkedMapOf()
    private val hypothesisRows: MutableMap<String, Hypothesis> = linkedMapOf()
    private val mandatoryRows: MutableMap<String, MandatoryRequirement> = linkedMapOf()
    private val attributionRows: MutableMap<String, RcaArbitrationOutcome> = linkedMapOf()
    private val attributionRowIds: MutableMap<String, Long> = mutableMapOf()
    private val incidentRows: MutableMap<Long, Long> = linkedMapOf()
    private val knowledgeRows: MutableMap<String, KnowledgeCandidateRequest> = linkedMapOf()
    private var nextRowId: Long = 1

    /** 已写入的证据行。顺序即写入顺序，便于人读输出。 */
    val evidence: List<EvidenceEntry> get() = evidenceRows.values.toList()

    val hypotheses: List<Hypothesis> get() = hypothesisRows.values.toList()

    val mandatoryRequirements: List<MandatoryRequirement> get() = mandatoryRows.values.toList()

    val attributions: List<RcaArbitrationOutcome> get() = attributionRows.values.toList()

    val incidentCount: Int get() = incidentRows.size

    val knowledgeCandidateCount: Int get() = knowledgeRows.size

    override fun saveEvidence(taskId: Long, evidence: List<EvidenceEntry>) {
        evidence.forEach { entry -> evidenceRows.putIfAbsent("$taskId#${entry.evidenceId}", entry) }
    }

    override fun saveHypotheses(taskId: Long, hypotheses: List<Hypothesis>) {
        hypotheses.forEach { hypothesis ->
            hypothesisRows.putIfAbsent("$taskId#${hypothesis.code}#${hypothesis.revision}", hypothesis)
        }
    }

    override fun saveMandatoryEvidence(taskId: Long, requirements: List<MandatoryRequirement>) {
        requirements.forEach { requirement -> mandatoryRows.putIfAbsent("$taskId#${requirement.code}", requirement) }
    }

    override fun saveArbitration(outcome: RcaArbitrationOutcome): Long {
        // 归因按 (episodeId, revision) 定位：同一任务重放时命中同一行，返回同一个主键，
        // 后面的 Incident 关联与知识候选才不会挂到另一行上。
        val key = "${outcome.attribution.episodeId}#${outcome.attribution.revision}"
        attributionRows.putIfAbsent(key, outcome)
        return attributionRowIds.getOrPut(key) { nextRowId++ }
    }

    override fun linkIncident(attributionRowId: Long, outcome: RcaArbitrationOutcome): Long {
        // 实现侧独立再判一次：调用方的判断是业务约束，这里是最后一道防线。
        require(outcome.autoLinkPermitted) {
            "归因等级 ${outcome.attribution.level.storageCode} 不允许自动关联 Incident"
        }
        return incidentRows.getOrPut(attributionRowId) { INCIDENT_ID }
    }

    override fun saveKnowledgeCandidate(request: KnowledgeCandidateRequest): Long? {
        val level = EpisodeAttributionLevel.fromStorage(request.attributionLevelCode)
        // 「不够格」是正常结果：歧义与未归因的结论入库会污染后续检索。
        if (level?.actionable != true) return null
        val key = "${request.taskId}#${request.signatureCanonical}"
        knowledgeRows.putIfAbsent(key, request)
        return knowledgeRows.keys.indexOf(key).toLong() + 1
    }

    private companion object {
        /** 夹具里的单条 Incident：回放不建 Incident 表，用固定 ID 表示「关联成功」。 */
        const val INCIDENT_ID: Long = 414_001L
    }
}
