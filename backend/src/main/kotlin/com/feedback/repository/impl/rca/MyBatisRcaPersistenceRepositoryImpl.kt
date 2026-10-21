package com.feedback.repository.impl.rca

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.agent.rca.domain.ClaimEvidencePair
import com.feedback.agent.rca.domain.EpisodeAttribution
import com.feedback.agent.rca.domain.EpisodeAttributionLevel
import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.MandatoryCompletion
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.RcaArbitrationOutcome
import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.graph.KnowledgeCandidateRequest
import com.feedback.agent.rca.graph.RcaPersistencePort
import com.feedback.mapper.rca.RcaPersistenceMapper
import com.feedback.model.po.rca.RcaAttributionInsertPo
import com.feedback.model.po.rca.RcaClaimPairInsertPo
import com.feedback.model.po.rca.RcaEvidenceInsertPo
import com.feedback.model.po.rca.RcaHypothesisInsertPo
import com.feedback.model.po.rca.RcaIncidentInsertPo
import com.feedback.model.po.rca.RcaKnowledgeCandidateInsertPo
import com.feedback.model.po.rca.RcaMandatoryEvidenceInsertPo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * RCA 持久化端口的 MyBatis 实现。
 *
 * 位置在 `repository/impl/rca/` 而不是 `agent/rca/`：本类直接依赖
 * `com.feedback.mapper.rca.RcaPersistenceMapper`，属于数据访问实现。
 * 放在 `agent/rca/` 下会让那个运行时依赖 MyBatis，而它的离线回放刻意不连库。
 *
 * ## 为什么不开事务
 *
 * 本类的六个写方法各自独立提交，不组成一个事务。这不是省事，而是**幂等性已经把
 * 事务要解决的问题解决掉了**：持久化节点会重放，所以任一方法中途失败后再跑一次，
 * 已经写成功的部分会打到同一行上（证据/假设/强制证据/归因靠唯一键，
 * 引用校验靠判存在，知识候选靠先查后插）。反过来，如果包成一个大事务，
 * 「归因已写库」这个中间状态就不存在了，而 `RcaPersistNode` 的恢复设计恰恰
 * 假定它存在——用一个大事务把它抹掉，会让崩溃恢复的推理少掉一个已知窗口。
 *
 * ### 唯一的例外：`saveArbitration` 内部是原子的
 *
 * 这个方法加了 `@Transactional`，是六个写方法里唯一一个。它没有违反上面那段话：
 * 「六个方法不组成一个事务」说的是**方法之间**，而这里包起来的是**方法内部**
 * （归因行 → 逐对引用校验 → 领域事件）三条语句。跨方法的那两个窗口一个都没关：
 * 「证据已写、归因未写」还在，「归因已写、任务行未置终态」也还在。
 *
 * 为什么这一个方法需要：「归因已写库」这个中间状态是可观测的，而它的构成部分
 * （归因行与它的依据）必须一起出现或一起不出现——一行声称「这是结论」的归因
 * 配上残缺的依据，比没有归因更难判读。
 *
 * 顺带被这个方法本地化掉的还有领域事件的原子性：见
 * [MyBatisRcaDomainEventJournalRepository] 关于「必须与状态迁移同事务」的说明。
 * 注意事件的**版本号**是一条独立的读（[RcaPersistenceMapper.findTaskStateVersion]），
 * 它在同一个事务里，因此读到的是与这次归因写入同一时刻的版本。
 *
 * ## 落库前的三道本地校验
 *
 * 有三类行在库层面就是非法的，本类在写入前拦掉并记 WARN，而不是让语句报错：
 *
 * 1. `content_digest` 不是小写 64 位十六进制 → `chk_rca_evidence_digest` 会拒绝；
 * 2. `coverage_type = COHORT` 但缺分母或对照组 → `chk_rca_evidence_cohort_complete` 会拒绝；
 * 3. `SATISFIED` 却没有 `satisfied_by_evidence_id`，或 `FAILED_GAP` 却没有失败语义
 *    → `chk_rca_mandatory_terminal` 会拒绝。
 *
 * 拦掉而不是改造：这三类的共同点是「用一个看起来完整的值盖住缺的东西」，
 * 而它们各自对应一条本系统最不该违反的约束（没有分母的范围性结论、
 * 无法回源的强制证据项）。硬写会失败，编一个值会造假，所以只剩显式跳过并留下记录。
 * 跳过**不是静默**：每一次都有 WARN 带任务与对象标识，交付文档里也记了这条边界。
 */
@Repository
class MyBatisRcaPersistenceRepositoryImpl(
    private val mapper: RcaPersistenceMapper,
    private val journal: RcaDomainEventJournal
) : RcaPersistencePort {

    private val log = LoggerFactory.getLogger(MyBatisRcaPersistenceRepositoryImpl::class.java)

    /**
     * JSON 列由本类拼好再传入 SQL。
     *
     * 刻意不复用 Spring 容器的 ObjectMapper：这个映射只涉及「字符串数组」一种形状，
     * 借用全局配置会让将来某次全局序列化配置调整悄悄改变落库内容。
     */
    private val json = ObjectMapper()

    override fun saveEvidence(taskId: Long, evidence: List<EvidenceEntry>) {
        evidence.forEach { entry ->
            val digest = entry.contentDigest
            if (!DIGEST_PATTERN.matches(digest)) {
                log.warn(
                    "RCA_EVIDENCE_DIGEST_INVALID task={} evidence={}：摘要不是小写 64 位十六进制，跳过写入",
                    taskId,
                    entry.evidenceId
                )
                return@forEach
            }
            if (entry.coverageType == EvidenceCoverage.COHORT && !entry.cohortComplete) {
                log.warn(
                    "RCA_EVIDENCE_COHORT_INCOMPLETE task={} evidence={}：范围性证据缺分母或对照组，跳过写入",
                    taskId,
                    entry.evidenceId
                )
                return@forEach
            }
            mapper.upsertEvidence(
                RcaEvidenceInsertPo(
                    taskId = taskId,
                    evidenceId = entry.evidenceId.fit(EVIDENCE_ID_MAX),
                    evidenceType = entry.type.storageCode,
                    sourceTool = entry.sourceTool.fit(SOURCE_TOOL_MAX),
                    sourceRef = entry.sourceRef.fit(SOURCE_REF_MAX),
                    coverageType = entry.coverageType.storageCode,
                    coverageScope = entry.coverageScope.fit(COVERAGE_SCOPE_MAX),
                    numerator = entry.numerator,
                    denominator = entry.denominator,
                    controlGroup = entry.controlGroup?.fit(CONTROL_GROUP_MAX),
                    observedFrom = entry.observedFrom,
                    observedTo = entry.observedTo,
                    timeWatermark = entry.timeWatermark,
                    permissionScope = entry.permissionScope.storageCode,
                    contentDigest = digest,
                    summary = entry.summary.fit(SUMMARY_MAX),
                    // 领域层没有对应的字段，因此这一列**恒为 NULL**。
                    // 不回源指针到 EvidenceEntry 里补一个：`sourceRef` 已经是回源指针
                    // （日志正文、堆栈正文都按它取），再补一个字段会让「按哪个回源」
                    // 这个问题出现两个答案，而人工复核只会试其中一个。
                    rawReference = null,
                    revision = entry.revision
                )
            )
        }
    }

    override fun saveHypotheses(taskId: Long, hypotheses: List<Hypothesis>) {
        hypotheses.forEach { hypothesis ->
            mapper.upsertHypothesis(
                RcaHypothesisInsertPo(
                    taskId = taskId,
                    hypothesisCode = hypothesis.code.fit(HYPOTHESIS_CODE_MAX),
                    statement = hypothesis.statement.fit(STATEMENT_MAX),
                    mechanismNote = hypothesis.mechanismNote?.fit(MECHANISM_MAX),
                    confidenceLevel = hypothesis.confidence.storageCode,
                    // 置信分没有来源：领域层用等级而不是概率表达支持强度，
                    // 这里不把等级换算成一个数字填进去——那会凭空造出一个统计口径。
                    confidenceScore = null,
                    hypothesisStatus = hypothesis.status.storageCode,
                    supportingEvidenceJson = json.writeValueAsString(hypothesis.supportingEvidenceIds),
                    refutingEvidenceJson = json.writeValueAsString(hypothesis.refutingEvidenceIds),
                    unresolvedRefutationCount = hypothesis.unresolvedRefutationCount,
                    revision = hypothesis.revision
                )
            )
        }
        // 合并链接在全部修订落库之后回填：目标行必须先存在，子查询才解析得到主键。
        hypotheses.forEach { hypothesis ->
            val target = hypothesis.mergedIntoCode ?: return@forEach
            mapper.linkHypothesisMerge(taskId, hypothesis.code, target)
        }
    }

    override fun saveMandatoryEvidence(taskId: Long, requirements: List<MandatoryRequirement>) {
        requirements.forEach { requirement ->
            val evidenceId = requirement.satisfiedByEvidenceId
            val failure = requirement.failureSemantic
            if (requirement.completion == MandatoryCompletion.SATISFIED && evidenceId == null) {
                log.warn(
                    "RCA_MANDATORY_UNBACKED task={} requirement={}：完成态没有回源证据，跳过写入",
                    taskId,
                    requirement.code
                )
                return@forEach
            }
            if (requirement.completion == MandatoryCompletion.FAILED_GAP && failure == null) {
                log.warn(
                    "RCA_MANDATORY_SEMANTIC_LOST task={} requirement={}：缺口态没有失败语义，跳过写入",
                    taskId,
                    requirement.code
                )
                return@forEach
            }
            mapper.upsertMandatoryEvidence(
                RcaMandatoryEvidenceInsertPo(
                    taskId = taskId,
                    requirementCode = requirement.code.fit(REQUIREMENT_CODE_MAX),
                    requirementType = requirement.type.storageCode,
                    triggerRule = requirement.triggerRule.fit(TRIGGER_RULE_MAX),
                    requiredReason = requirement.requiredReason.fit(REQUIRED_REASON_MAX),
                    completionStatus = requirement.completion.storageCode,
                    satisfiedByEvidenceId = evidenceId?.fit(EVIDENCE_ID_MAX),
                    failureSemantic = failure?.storageCode,
                    waiverReason = requirement.waiverReason?.fit(WAIVER_REASON_MAX)
                )
            )
        }
    }

    /**
     * 写归因行 + 逐对引用校验 + 领域事件，三者一个事务。详见类文档里「唯一的例外」一节。
     *
     * 事件在这里发而不是在 `RcaPersistNode` 里发，理由与
     * [com.feedback.agent.rca.runtime.RcaTaskLeaseAdapter] 相同：只有本层看得到事务边界。
     */
    @Transactional
    override fun saveArbitration(outcome: RcaArbitrationOutcome): Long {
        val attribution = outcome.attribution
        val row = RcaAttributionInsertPo(
            episodeId = attribution.episodeId,
            rcaTaskId = attribution.rcaTaskId,
            rootCauseSignature = attribution.signature.canonical.fit(SIGNATURE_MAX),
            attributionLevel = attribution.level.storageCode,
            attributionRevision = attribution.revision,
            basisEvidenceJson = json.writeValueAsString(attribution.basisEvidenceIds),
            coverageNote = attribution.coverageNote?.fit(COVERAGE_NOTE_MAX),
            autoLinked = attribution.autoLinked,
            decidedAt = attribution.decidedAt,
            decidedBy = attribution.decidedBy
        )
        mapper.upsertAttribution(row)
        val attributionRowId = row.id

        // 逐对校验结果按 claim 落库。claim_code 的格式是「假设编码#证据ID」，
        // 取第一个 '#' 之前的部分作为假设编码——假设编码本身不含 '#'，
        // 而证据 ID 可能含 ':' 等字符，所以只能按第一个分隔符切。
        outcome.claimPairs.forEach { pair ->
            mapper.insertClaimPairIfAbsent(pair.toInsertPo(attribution.rcaTaskId))
        }
        recordAttributionEvent(attribution)
        return attributionRowId
    }

    /**
     * 记一条 `ATTRIBUTION_RECORDED`。
     *
     * ## 任务行读不到时**跳过**事件，而不是编一个版本号
     *
     * `aggregate_version` 是 NOT NULL，而 0 是合法值——编一个 0 不会有任何约束拦下来，
     * 于是事件流里会出现一条版本为 0 的归因记录，看起来完全正常。归因行本身照写：
     * 它是这次执行真正的产物，不该因为事件表的版本号取不到而回滚
     * （归因行与任务行之间有外键约束，真的取不到只可能是数据已经被别处改坏，
     * 那时回滚归因不会让情况变好，只会让两处都少一条线索）。
     *
     * ## 载荷里为什么没有 `root_cause_signature`
     *
     * 它是从反馈文本归一出来的字符串，进不了会离开本进程的载荷
     * （见 [RcaDomainEvent] 的载荷禁令）。事件与归因行之间的关联由
     * `(task_id, episode_id, attribution_revision)` 唯一确定，不需要把签名搬过去。
     */
    private fun recordAttributionEvent(attribution: EpisodeAttribution) {
        val stateVersion = mapper.findTaskStateVersion(attribution.rcaTaskId)
        if (stateVersion == null) {
            log.warn(
                "RCA_ATTRIBUTION_EVENT_SKIPPED task={}：读不到任务行，无法确定事件的聚合版本，" +
                    "归因行已写入但未记领域事件",
                attribution.rcaTaskId
            )
            return
        }
        journal.record(
            RcaDomainEvent(
                aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
                aggregateId = attribution.rcaTaskId,
                aggregateVersion = stateVersion,
                type = RcaEventType.ATTRIBUTION_RECORDED,
                taskId = attribution.rcaTaskId,
                episodeId = attribution.episodeId,
                // 归因写入发生在图内最后一个节点上，此时线程上没有链路标识可用
                // （它只存在于租约令牌里，而本层看不到令牌）。留 null 而不是从别处借一个：
                // 「这次执行有没有链路标识」这个问题的答案不能由本层来猜。
                traceId = null,
                payload = linkedMapOf(
                    "attributionLevel" to attribution.level.storageCode,
                    "attributionRevision" to attribution.revision,
                    "autoLinked" to attribution.autoLinked
                )
            )
        )
    }

    override fun linkIncident(attributionRowId: Long, outcome: RcaArbitrationOutcome): Long {
        val level = outcome.attribution.level
        // 独立再判一次等级。调用方 RcaPersistNode 已经按 autoLinkPermitted 判过，
        // 但那是业务约束；库上的 chk_episode_attribution_incident_link 是最后一道防线。
        // 两道都留，是为了让将来任何新的调用点都绕不过去。
        if (!level.autoLinkPermitted) {
            log.warn(
                "RCA_INCIDENT_LINK_REFUSED attributionRow={} level={}：该等级不允许关联 Incident",
                attributionRowId,
                level.storageCode
            )
            return NO_INCIDENT
        }

        val title = incidentTitle(outcome).fit(INCIDENT_TITLE_MAX)
        val incidentId = mapper.findOpenIncidentIdByTitle(title)
            ?: run {
                val row = RcaIncidentInsertPo(
                    publicId = UUID.randomUUID().toString(),
                    incidentTitle = title,
                    incidentSeverity = DEFAULT_INCIDENT_SEVERITY
                )
                mapper.insertIncident(row)
                row.id
            }

        val linked = mapper.linkAttributionToIncident(attributionRowId, incidentId)
        if (linked == 0) {
            // 影响行数为 0 说明归因行已经不满足关联条件（等级被改过，或已经关联过）。
            // 不重试：这不是暂时性失败，重试只会得到同一个 0。
            log.warn(
                "RCA_INCIDENT_LINK_NOOP attributionRow={} incident={}：归因行未满足关联条件",
                attributionRowId,
                incidentId
            )
        }
        return incidentId
    }

    override fun saveKnowledgeCandidate(request: KnowledgeCandidateRequest): Long? {
        val level = EpisodeAttributionLevel.fromStorage(request.attributionLevelCode)
        // 「不够格」是正常结果而不是错误：歧义与未归因的结论入库会污染后续检索。
        if (level == null || !level.actionable) return null

        // 先查后插：t_ai_knowledge 的唯一键是 public_id（每次随机），挡不住重放，
        // 所以幂等只能靠业务键 (source_rca_id, knowledge_source)。
        mapper.findKnowledgeCandidateId(request.taskId)?.let { return it }

        val row = RcaKnowledgeCandidateInsertPo(
            publicId = UUID.randomUUID().toString(),
            knowledgeTitle = request.signatureCanonical.fit(KNOWLEDGE_TITLE_MAX),
            knowledgeContent = request.summary,
            knowledgeTagsJson = json.writeValueAsString(
                listOf(request.signatureCanonical, level.storageCode)
            ),
            sourceRcaId = request.taskId
        )
        mapper.insertKnowledgeCandidate(row)
        return row.id
    }

    private fun ClaimEvidencePair.toInsertPo(taskId: Long): RcaClaimPairInsertPo = RcaClaimPairInsertPo(
        taskId = taskId,
        hypothesisCode = claimCode.substringBefore(CLAIM_CODE_SEPARATOR).takeIf { it.isNotEmpty() },
        claimCode = claimCode.fit(CLAIM_CODE_MAX),
        claimType = claimType.storageCode,
        claimText = claimText.fit(CLAIM_TEXT_MAX),
        evidenceId = evidenceId.fit(EVIDENCE_ID_MAX),
        evidenceCoverageType = evidenceCoverage.storageCode,
        requiredCoverageType = claimType.requiredCoverage.storageCode,
        supportVerdict = verdict.storageCode,
        verdictReason = verdictReason?.fit(VERDICT_REASON_MAX)
    )

    /**
     * Incident 标题：由稳定签名的组件、故障类型与错误签名拼出。
     *
     * 不含影响面与时间——它们是动态关联特征，写进标题会让同一条根因的后续归因
     * 匹配不上已有 Incident，影响面统计随即分裂。
     */
    private fun incidentTitle(outcome: RcaArbitrationOutcome): String {
        val signature = outcome.attribution.signature
        return "RCA:${signature.component}:${signature.faultType}:${signature.errorSignature}"
    }

    /** 超长即截断并留痕。静默截断会让「落库内容与领域对象不一致」这件事无人知晓。 */
    private fun String.fit(max: Int): String {
        if (length <= max) return this
        log.warn("RCA_PERSIST_VALUE_TRUNCATED max={} actual={}：落库值被截断", max, length)
        return substring(0, max)
    }

    private companion object {
        /** 与 `t_incident.incident_severity` 的 CHECK 一致；RCA 不开更高档位，交人工重定级。 */
        const val DEFAULT_INCIDENT_SEVERITY = "P3"

        /** 关联未生效时的返回值。Incident 主键从 1 开始，0 因此可以安全地表示「没有」。 */
        const val NO_INCIDENT = 0L

        /** claim_code 的假设编码与证据 ID 之间的分隔符，与仲裁节点拼接时保持一致。 */
        const val CLAIM_CODE_SEPARATOR = '#'

        val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")

        // 以下上限与 V242 / V2 的列宽一一对应，改动前先核对迁移。
        const val EVIDENCE_ID_MAX = 64
        const val SOURCE_TOOL_MAX = 40
        const val SOURCE_REF_MAX = 300
        const val COVERAGE_SCOPE_MAX = 300
        const val CONTROL_GROUP_MAX = 200
        const val SUMMARY_MAX = 1000
        const val HYPOTHESIS_CODE_MAX = 40
        const val STATEMENT_MAX = 500
        const val MECHANISM_MAX = 1000
        const val REQUIREMENT_CODE_MAX = 60
        const val TRIGGER_RULE_MAX = 300
        const val REQUIRED_REASON_MAX = 300
        const val WAIVER_REASON_MAX = 300
        const val SIGNATURE_MAX = 300
        const val COVERAGE_NOTE_MAX = 500
        const val CLAIM_CODE_MAX = 60
        const val CLAIM_TEXT_MAX = 1000
        const val VERDICT_REASON_MAX = 500
        const val INCIDENT_TITLE_MAX = 200
        const val KNOWLEDGE_TITLE_MAX = 200
    }
}
