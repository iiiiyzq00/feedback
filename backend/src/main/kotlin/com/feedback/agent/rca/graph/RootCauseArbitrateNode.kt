package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.ClaimEvidencePair
import com.feedback.agent.rca.domain.ClaimSupportVerdict
import com.feedback.agent.rca.domain.ClaimType
import com.feedback.agent.rca.domain.EpisodeAttribution
import com.feedback.agent.rca.domain.EpisodeAttributionLevel
import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.MandatoryCompletion
import com.feedback.agent.rca.domain.RcaArbitrationOutcome
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaFallbackEdge
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.RootCauseSignature
import com.feedback.agent.rca.domain.rank
import com.feedback.agent.rca.tools.EpisodeCoverageTarget
import com.feedback.agent.rca.tools.EvidenceScopeMatcher
import com.feedback.agent.rca.tools.EvidenceSourceRef

/**
 * ROOT_CAUSE_ARBITRATE：纯确定性节点，对模型给出的候选做四项检查。
 *
 * 四项检查各自封堵一类失败：
 *
 * 1. **引用可回源**：claim 引用的 evidence ID 必须真的在账本里。封堵「引用看起来合理但不存在」。
 * 2. **覆盖到目标对象**：证据必须覆盖本 Episode。封堵「拿 A 的日志证明 B 的问题」——
 *    代表日志只能直接证明被查询的那一个 Episode。
 * 3. **引用粒度够**：范围性结论必须至少有一条 cohort 级引用。封堵失败复盘一，
 *    即「用单条新版本日志支持故障集中在新版本」。
 * 4. **主要反例已处理**：未排除的强反证必须被机制链解释，否则降级而不是强行通过。
 *
 * 关于第 3 条有一个必须讲清楚的判定口径：**粒度检查是 claim 级而不是 pair 级**。
 * 一条范围性 claim 引用了三份证据、其中一份是带分母与对照组的 cohort 指标，
 * 另外两份是单点日志——这是完全正常的取证方式，单点日志是佐证。
 * 如果按 pair 级判「每一条引用都必须是 cohort」，正常取证反而会被判不通过。
 * 所以逐对判定只负责回源与覆盖，粒度是否够由 claim 级单独裁决。
 * 逐对结果里的 `PARTIAL_OVERSCOPED` 是**诊断信息**（哪些引用被用错了地方），不是门禁。
 *
 * 检查不通过时本节点**不直接出结论，而是请求回退补证**，见 [requestFallback]。
 */
class RootCauseArbitrateNode(
    private val clock: RcaClock = RcaClock.system()
) : RcaNodeHandler {

    override val node: RcaNode = RcaNode.ROOT_CAUSE_ARBITRATE

    override fun execute(context: RcaNodeContext): RcaNodeOutcome {
        val state = context.state
        val candidate = state.leadingHypothesis
            ?: return degraded(
                state,
                RcaStopReason.HUMAN_REQUIRED,
                "没有任何活跃假设可供仲裁，转人工而不是产出一个空结论"
            )

        // 检查 1：引用可回源。
        val citedEvidence = candidate.supportingEvidenceIds.mapNotNull { id ->
            state.evidenceLedger.firstOrNull { it.evidenceId == id }
        }
        val danglingIds = candidate.supportingEvidenceIds.filter { id ->
            state.evidenceLedger.none { it.evidenceId == id }
        }
        if (citedEvidence.isEmpty()) {
            return degraded(
                state,
                RcaStopReason.HUMAN_REQUIRED,
                "假设 ${candidate.code} 引用的证据全部无法回源：${candidate.supportingEvidenceIds.joinToString()}"
            )
        }

        // 检查 2：证据覆盖目标对象。判定规则由工具层拥有（它才持有回源指针的格式），
        // 这里只消费结论，不自己拼前缀比对。
        val coverageTarget = EpisodeCoverageTarget.of(state.problemProfile)
        val coveringEvidence = citedEvidence.filter { EvidenceScopeMatcher.covers(it, coverageTarget) }

        // 检查 3：引用粒度。claim 类型由假设文本推导，不采信模型自述的类型。
        val claimType = classifyClaim(candidate.statement, candidate.mechanismNote)
        val pairs = citedEvidence.map { evidence -> evaluatePair(evidence, coverageTarget, candidate, claimType) }
        val danglingPairs = pairs.filter { it.verdict == ClaimSupportVerdict.UNSUPPORTED }
        val underscoppedPairs = pairs.filter { it.verdict == ClaimSupportVerdict.PARTIAL_OVERSCOPED }
        val gradeOk = hasSufficientGrade(claimType, citedEvidence, pairs)

        // 检查 4：主要反例已处理。
        val mandatoryGaps = state.mandatoryPlan.requirements.filter { it.completion == MandatoryCompletion.FAILED_GAP }
        val unresolvedRefutations = candidate.unresolvedRefutationCount

        // 四项检查归并成一个违规类型；命中即优先尝试回退补证，而不是硬着头皮出结论。
        val violation = classifyViolation(
            danglingCount = danglingPairs.size,
            gradeOk = gradeOk,
            unresolvedRefutations = unresolvedRefutations,
            mandatoryGapCount = mandatoryGaps.size
        )

        // 回退额度 = 上限减去「本节点此前进入过几次」，也就是 [RcaNodeContext.attemptNo]。
        // 首次进入 attemptNo 为 0，额度恰好等于 [RcaFallbackEdge.MAX_ARBITRATE_REVISIONS] 次。
        val revisionsLeft = RcaFallbackEdge.MAX_ARBITRATE_REVISIONS - context.attemptNo
        if (violation != null && revisionsLeft > 0) {
            return requestFallback(state, candidate, violation, claimType, revisionsLeft, context.attemptNo)
        }

        // 走到这里有两种情况：四项检查全部通过，或者违规但回退额度已用尽。
        // 后者的归因等级会被 [resolveAttributionLevel] 降到 AMBIGUOUS / UNATTRIBUTED，
        // 也就是说「额度用尽」不会让结论变强，只会让它变成候选。
        val level = resolveAttributionLevel(
            violation = violation,
            // 「有直接证据」的判据是「存在一条覆盖本对象的单点级现场证据」，判否的只是
            // 无法回源这一类。**粒度不足不算在这里**：粒度是 claim 级的事，由 hasSufficientGrade
            // 单独裁决。若把逐对的 PARTIAL_OVERSCOPED 也算成「没有直接证据」，
            // 范围性与因果性结论就永远到不了直接确认——那两类 claim 引用的单点证据
            // 按定义都会被判粒度不足，而它们恰恰是最需要现场证据的场景。
            hasDirectEvidence = citedEvidence.zip(pairs).any { (evidence, pair) ->
                evidence.coverageType == EvidenceCoverage.EPISODE &&
                    pair.verdict != ClaimSupportVerdict.UNSUPPORTED &&
                    EvidenceScopeMatcher.covers(evidence, coverageTarget)
            },
            hasCoveringEvidence = coveringEvidence.isNotEmpty()
        )
        val finalStatus = resolveFinalStatus(violation)

        val signature = RootCauseSignature(
            // 稳定签名只含租户/依赖范围、组件、故障类型、错误签名、接口类型。
            // 影响面、时间重叠度、版本分布是动态关联特征，绝不能写进来——
            // 写进去会导致新反馈因时间不同而匹配不上已有 Incident，影响面会不断分裂。
            scope = state.problemProfile.tenantKey,
            component = componentOf(coveringEvidence, state.problemProfile.appId),
            faultType = faultTypeOf(candidate),
            errorSignature = state.problemProfile.errorSignature ?: state.problemProfile.problemKey,
            endpointType = endpointTypeOf(coveringEvidence)
        )

        val attribution = EpisodeAttribution(
            episodeId = state.episodeId,
            rcaTaskId = state.taskId,
            signature = signature,
            level = level,
            revision = state.stateVersion,
            basisEvidenceIds = coveringEvidence.map(EvidenceEntry::evidenceId)
                .ifEmpty { citedEvidence.map(EvidenceEntry::evidenceId) },
            coverageNote = coverageNote(level, coveringEvidence.size, claimType, underscoppedPairs.size),
            decidedAt = clock.now()
        )

        val arbitration = RcaArbitrationOutcome(
            attribution = attribution,
            claimPairs = pairs,
            violationCode = violation?.storageCode,
            finalStatus = finalStatus,
            stopReason = resolveStopReason(violation, state)
        )

        val next = state.copy(
            hypotheses = state.hypotheses.map { hypothesis ->
                if (hypothesis.code == candidate.code) {
                    hypothesis.copy(confidence = arbitratedConfidence(level, hypothesis.confidence))
                } else {
                    hypothesis
                }
            },
            // 归因随状态走：任务在「仲裁完成、尚未落库」这个窗口崩溃后，
            // 恢复只需读快照就能接着落库，不需要任何进程外的旁路传递。
            arbitration = arbitration,
            stateVersion = state.stateVersion + 1
        )

        return RcaNodeOutcome(
            state = next,
            next = RcaNode.RCA_PERSIST,
            enterReason = "调查循环提交停止后进入仲裁，第 ${context.attemptNo + 1} 次",
            leaveReason = buildString {
                append(
                    "claim 类型 ${claimType.storageCode}、归因等级 ${level.storageCode}、" +
                        "终态 ${finalStatus.storageCode}："
                )
                append("可回源引用 ${citedEvidence.size} 条、覆盖本对象 ${coveringEvidence.size} 条、")
                append("无法回源或不覆盖 ${danglingPairs.size} 对、粒度不足 ${underscoppedPairs.size} 对、")
                append("粒度门禁 ${if (gradeOk) "通过" else "未通过"}、")
                append("未排除反证 $unresolvedRefutations 条、强制证据缺口 ${mandatoryGaps.size} 项")
                if (violation != null) append("；违规类型 ${violation.storageCode} 但回退额度已用尽，降级出候选结论")
                if (danglingIds.isNotEmpty()) append("；无法回源的引用：${danglingIds.joinToString()}")
            }
            // 这里刻意**不**声明终态：归因等级已经是终态，但任务还不能收口。
            // 编排器的路由规则是「终态优先于 next」，在本节点声明终态会让状态图跳过持久化节点直接结束——
            // 于是每条走通的调查都变成「结论算出来了、一行都没落库」。终态由最后一个节点
            // [RcaPersistNode] 声明，它已经从 [RcaArbitrationOutcome.finalStatus] 读到了同一个值。
        )
    }

    // -----------------------------------------------------------------------
    // 回退：检查不通过时回调查循环补证
    // -----------------------------------------------------------------------

    /**
     * 请求沿回退边回到 [RcaNode.AGENT_INVESTIGATE] 补证。
     *
     * 三点设计意图：
     *
     * 1. **回退不是失败，是补证**。所以状态继续向前携带（假设、证据、历史全部保留），
     *    并把「仲裁为什么不放行」写进缺口，让下一轮模型看得到自己在被要求补什么。
     * 2. **回退必须有额度**。额度用尽时不再回退，由调用点降级出候选结论，
     *    否则「仲裁不通过就回退」会形成活锁——这也是 [RcaFallbackEdge.MAX_ARBITRATE_REVISIONS]
     *    存在的唯一理由。
     * 3. **违规类型决定回退边**，不是笼统地「回退重来」：
     *    强制证据缺 → [RcaFallbackEdge.MANDATORY_INCOMPLETE]；
     *    反证未排除 → [RcaFallbackEdge.REFUTATION_UNRESOLVED]；
     *    引用撑不起结论 → [RcaFallbackEdge.CITATION_UNSUPPORTED]。
     *    落库的 trigger 因此能直接回答「这一轮回退是在补哪一类证据」。
     */
    private fun requestFallback(
        state: RcaSharedState,
        candidate: Hypothesis,
        violation: FourCheckViolation,
        claimType: ClaimType,
        revisionsLeft: Int,
        attemptNo: Int
    ): RcaNodeOutcome {
        val edge = violation.fallbackEdge
        val explanation = "仲裁四项检查未通过（${violation.storageCode}）：${violation.description}；" +
            "第 ${attemptNo + 1} 次进入本节点，剩余回退额度 $revisionsLeft 次，回调查循环补证"
        // 回退时把违规原因写进缺口，下一轮模型的上下文里就会出现「它在被要求补什么」。
        val next = state.copy(
            gapsAndBudget = state.gapsAndBudget.copy(
                knownGaps = (state.gapsAndBudget.knownGaps + explanation).distinct()
            ),
            // 假设本身不下调：它还没被证伪，只是引用撑不住，降级由最终那次仲裁统一做。
            stateVersion = state.stateVersion + 1
        )
        return RcaNodeOutcome(
            state = next,
            next = edge.to,
            enterReason = "调查循环提交停止后进入仲裁，第 ${attemptNo + 1} 次",
            leaveReason = explanation,
            fallback = edge
        )
    }

    private fun classifyViolation(
        danglingCount: Int,
        gradeOk: Boolean,
        unresolvedRefutations: Int,
        mandatoryGapCount: Int
    ): FourCheckViolation? = when {
        // 顺序即优先级：强制证据缺口是最硬的理由，其次是引用不可回源，再次是粒度与反证。
        mandatoryGapCount > 0 -> FourCheckViolation.MANDATORY_INCOMPLETE
        danglingCount > 0 -> FourCheckViolation.CITATION_UNSUPPORTED
        !gradeOk -> FourCheckViolation.CITATION_UNSUPPORTED
        unresolvedRefutations > 0 -> FourCheckViolation.REFUTATION_UNRESOLVED
        else -> null
    }

    // -----------------------------------------------------------------------
    // 逐对判定与 claim 级粒度裁决
    // -----------------------------------------------------------------------

    /**
     * 逐对判定，只负责「回源 + 覆盖」两件事。
     *
     * [ClaimEvidencePair.evaluate] 内部也会按 claim 类型比一次粒度，返回的
     * `PARTIAL_OVERSCOPED` 在这里被当作诊断保留；它不直接决定门禁，
     * 门禁由 [hasSufficientGrade] 在 claim 级裁决。
     */
    private fun evaluatePair(
        evidence: EvidenceEntry,
        coverageTarget: EpisodeCoverageTarget,
        candidate: Hypothesis,
        claimType: ClaimType
    ): ClaimEvidencePair = ClaimEvidencePair(
        claimCode = "${candidate.code}#${evidence.evidenceId}",
        claimType = claimType,
        claimText = candidate.statement,
        evidenceId = evidence.evidenceId,
        evidenceCoverage = evidence.coverageType
    ).evaluate(evidenceExists = true, evidenceCoversTarget = EvidenceScopeMatcher.covers(evidence, coverageTarget))

    /**
     * claim 级粒度门禁：范围性结论至少要有一条粒度达标的引用。
     *
     * 判据有两层，缺一层就会被绕过：
     *
     * 1. **覆盖粒度够**：逐条比 [EvidenceCoverage] 的高低，取该 claim 类型要求的粒度。
     * 2. **证据本身完整**：要求队列粒度时，必须真的带分母与对照组
     *    （[EvidenceEntry.usableForRangeClaim]）。
     *
     * 第二层不能省。覆盖类型是工具**声明**的静态属性，而分母与对照组是这次调用
     * **实际取到**的东西：一次队列查询可能因上游样本不足而只回了一半数据，
     * 此时它的覆盖类型仍然是 COHORT。只看类型的话，「故障集中在新版本」这句话
     * 就能被一条没有分母的证据支撑，而分母恰恰是这句话能不能成立的全部依据。
     *
     * 注意判据是「至少一条引用达标」而不是「每条引用都达标」，
     * 这就是它与逐对 `PARTIAL_OVERSCOPED` 的区别，也是最容易被追问的一处。
     */
    private fun hasSufficientGrade(
        claimType: ClaimType,
        citedEvidence: List<EvidenceEntry>,
        pairs: List<ClaimEvidencePair>
    ): Boolean {
        val required = claimType.requiredCoverage
        // 用 zip 而不是按下标取：pairs 是由 citedEvidence 映射出来的，两者必须同序，
        // 按下标取只是「碰巧对」，一旦上游插入了过滤就会静默错位。
        return citedEvidence.zip(pairs).any { (evidence, pair) ->
            if (pair.verdict == ClaimSupportVerdict.UNSUPPORTED) {
                false
            } else {
                when (required) {
                    EvidenceCoverage.COHORT -> evidence.usableForRangeClaim
                    // 粒度排序复用领域层那一份，仲裁不自己再写一遍。
                    else -> evidence.coverageType.rank >= required.rank
                }
            }
        }
    }

    /**
     * 依据四项检查结果推导归因等级。
     *
     * 之所以归因等级要复用 `violation` 而不是重新算一遍：两者必须是同一个判据，
     * 否则会出现「回退理由是粒度不足、但归因等级却是直接确认」这种自相矛盾的落库结果。
     *
     * 分界线刻意放在「是否有单点级现场证据」而不是「是否有 cohort 证据」：
     * 队列指标的分母虽然覆盖本对象，但它不是现场证据，靠它归因属于继承而非直接观测。
     */
    private fun resolveAttributionLevel(
        violation: FourCheckViolation?,
        hasDirectEvidence: Boolean,
        hasCoveringEvidence: Boolean
    ): EpisodeAttributionLevel = when {
        !hasCoveringEvidence -> EpisodeAttributionLevel.UNATTRIBUTED
        violation == FourCheckViolation.MANDATORY_INCOMPLETE -> EpisodeAttributionLevel.UNATTRIBUTED
        violation != null -> EpisodeAttributionLevel.AMBIGUOUS
        hasDirectEvidence -> EpisodeAttributionLevel.DIRECT_CONFIRMED
        else -> EpisodeAttributionLevel.SUPPORTED_INHERITED
    }

    /**
     * 仲裁通过时的任务终态。
     *
     * 只有四项检查全过才给 [RcaTaskStatus.COMPLETED]；违规但额度用尽时，
     * 强制证据缺口与反证未排除都要人工去补现场证据，所以是 [RcaTaskStatus.NEEDS_HUMAN]；
     * 引用撑不起结论属于「有证据但覆盖不足」，是 [RcaTaskStatus.PARTIAL]。
     * 这个区分与 [StopEvaluator.resolveDegradedStatus] 的口径保持一致。
     */
    private fun resolveFinalStatus(violation: FourCheckViolation?): RcaTaskStatus = when (violation) {
        null -> RcaTaskStatus.COMPLETED
        FourCheckViolation.MANDATORY_INCOMPLETE,
        FourCheckViolation.REFUTATION_UNRESOLVED -> RcaTaskStatus.NEEDS_HUMAN

        FourCheckViolation.CITATION_UNSUPPORTED -> RcaTaskStatus.PARTIAL
    }

    private fun resolveStopReason(violation: FourCheckViolation?, state: RcaSharedState): RcaStopReason =
        when (violation) {
            // 检查全过时保留模型提案里的停止原因，仲裁不抢这个信息——它记录了模型的判断依据。
            null -> state.stopProposal?.reason ?: RcaStopReason.EVIDENCE_SUFFICIENT
            FourCheckViolation.MANDATORY_INCOMPLETE -> RcaStopReason.MANDATORY_TOOL_UNAVAILABLE
            FourCheckViolation.REFUTATION_UNRESOLVED -> RcaStopReason.UNRESOLVED_REFUTATION
            FourCheckViolation.CITATION_UNSUPPORTED -> RcaStopReason.LOW_INFORMATION_GAIN
        }

    /**
     * 仲裁后的置信等级。
     *
     * 关键约束：仲裁只能**降级不能升级**。理由是一旦仲裁能升级，模型就可以靠
     * 一次仲裁把「弱证据 + 强断言」变成高置信结论，而仲裁读的还是同一批证据。
     * 最高档位封在 [HypothesisConfidence.STRONG]——`CONFIRMED` 留给工程师。
     */
    private fun arbitratedConfidence(
        level: EpisodeAttributionLevel,
        current: HypothesisConfidence
    ): HypothesisConfidence = when (level) {
        EpisodeAttributionLevel.DIRECT_CONFIRMED -> minOf(current, HypothesisConfidence.STRONG)
        EpisodeAttributionLevel.SUPPORTED_INHERITED -> minOf(current, HypothesisConfidence.MODERATE)
        EpisodeAttributionLevel.AMBIGUOUS -> minOf(current, HypothesisConfidence.WEAK)
        else -> HypothesisConfidence.CANDIDATE
    }

    private fun degraded(state: RcaSharedState, reason: RcaStopReason, explanation: String): RcaNodeOutcome =
        RcaNodeOutcome(
            state = state,
            next = null,
            enterReason = "调查循环提交停止后进入仲裁",
            leaveReason = explanation,
            terminalStatus = RcaTaskStatus.NEEDS_HUMAN,
            stopReason = reason
        )

    // -----------------------------------------------------------------------
    // 文本到稳定字段的确定性映射
    // -----------------------------------------------------------------------

    /**
     * 由假设文本推导 claim 类型。
     *
     * 之所以要程序推导而不是采信模型自述：claim 类型直接决定粒度门禁的宽严，
     * 让模型自己声明类型等于让被检查方出考卷。代价是这个推导基于文本标记、比较粗糙，
     * 收入词典的标记因此保持可审计（见 companion object 里的常量）。
     */
    private fun classifyClaim(statement: String, mechanismNote: String?): ClaimType {
        val text = statement + " " + (mechanismNote ?: "")
        return when {
            RANGE_MARKERS.any(text::contains) -> ClaimType.RANGE
            MECHANISM_MARKERS.any(text::contains) -> ClaimType.CAUSAL
            else -> ClaimType.EPISODIC
        }
    }

    /**
     * 稳定签名里的组件。
     *
     * 候选由工具层给出（[EvidenceSourceRef.componentCandidates]），因为「这条证据的主体标识
     * 能不能当组件用」是工具知识：日志给首错点所在服务、发布变更给变更组件、服务健康给服务名，
     * 而版本号、堆栈指纹、资源 token 都不能当组件。
     *
     * 取不到任何候选时回退到应用级。回退而不是编一个组件名，是因为签名是 Incident 关联与
     * 知识检索的键：编出来的组件会让同一条经验分裂成多条，比粗一点更难修。
     */
    private fun componentOf(evidence: List<EvidenceEntry>, fallback: String): String = evidence
        .firstNotNullOfOrNull { entry ->
            EvidenceSourceRef.componentCandidates(entry.sourceTool, entry.subjectRefs).firstOrNull()
        }
        ?: fallback

    /**
     * 接口类型。
     *
     * 把 `HTTP 400` 归一成 `HTTP_400`：这是稳定签名的组成部分，带空格的写法会随文本来源
     * 漂移（摘要里是「返回 HTTP 400」，人工填写时可能写成「HTTP400」），
     * 而签名一旦漂移，同一故障的 Incident 就并不到一起。
     */
    private fun endpointTypeOf(evidence: List<EvidenceEntry>): String = evidence
        .firstNotNullOfOrNull { entry ->
            HTTP_STATUS_PATTERN.find(entry.summary)?.value?.replace(WHITESPACE_PATTERN, "_")
        }
        ?: UNSPECIFIED_ENDPOINT

    private fun faultTypeOf(candidate: Hypothesis): String = when {
        INCOMPATIBILITY_MARKERS.any(candidate.statement::contains) -> "PARAMETER_INCOMPATIBILITY"
        else -> "UNDETERMINED"
    }

    /**
     * 覆盖范围说明。DIRECT_CONFIRMED 且不是范围性结论时可以为空——
     * 这条归因直接来自本对象的现场证据，没有需要额外声明的作用域。
     */
    private fun coverageNote(
        level: EpisodeAttributionLevel,
        coveringCount: Int,
        claimType: ClaimType,
        underscoppedCount: Int
    ): String? {
        if (level == EpisodeAttributionLevel.DIRECT_CONFIRMED && claimType != ClaimType.RANGE) return null
        return buildString {
            append("覆盖到本对象的证据 $coveringCount 条")
            if (underscoppedCount > 0) {
                append("，其中 $underscoppedCount 条粒度不足以单独支撑 ${claimType.storageCode} 结论")
            }
            append("；该结论不覆盖其他 Episode，需要独立取证")
        }
    }

    private companion object {
        const val UNSPECIFIED_ENDPOINT = "UNSPECIFIED"

        /** 范围性表述标记。命中即要求至少一条 cohort 级引用。 */
        val RANGE_MARKERS: List<String> = listOf("集中", "主要", "大部分", "全量", "普遍", "占比", "显著")

        /** 机制链标记。命中即按因果类 claim 校验，要求簇级及以上引用。 */
        val MECHANISM_MARKERS: List<String> = listOf("导致", "引起", "因为", "机制", "触发")

        /** 参数不兼容类故障的表述标记，用于推导稳定签名里的故障类型。 */
        val INCOMPATIBILITY_MARKERS: List<String> = listOf("不兼容", "参数", "改名", "重命名", "契约")

        val HTTP_STATUS_PATTERN: Regex = Regex("HTTP\\s*\\d{3}")

        /** 归一接口类型时用于压缩空白；签名里不允许出现空格。 */
        val WHITESPACE_PATTERN: Regex = Regex("\\s+")
    }
}

/**
 * 四项检查归并出的违规类型。与 [RcaFallbackEdge] 一一对应，落库时用回退边的 trigger。
 */
enum class FourCheckViolation(val storageCode: String, val description: String, val fallbackEdge: RcaFallbackEdge) {
    MANDATORY_INCOMPLETE(
        "MANDATORY_EVIDENCE_INCOMPLETE",
        "强制证据计划仍有缺口，必要现场证据没拿到",
        RcaFallbackEdge.MANDATORY_INCOMPLETE
    ),
    REFUTATION_UNRESOLVED(
        "REFUTATION_UNRESOLVED",
        "存在未排除的强反证，两个机制无法区分",
        RcaFallbackEdge.REFUTATION_UNRESOLVED
    ),
    CITATION_UNSUPPORTED(
        "CITATION_UNSUPPORTED",
        "claim 引用的证据不存在、不覆盖目标对象，或粒度撑不起该结论",
        RcaFallbackEdge.CITATION_UNSUPPORTED
    )
}
