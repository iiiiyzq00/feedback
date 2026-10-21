package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.model.cluster.Cluster
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.cluster.stableClusterNameKey
import com.feedback.model.po.cluster.ClusterInsertPo
import com.feedback.model.po.cluster.ClusterLinkInsertPo
import com.feedback.model.po.cluster.ClusterCandidateSkipPo
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.service.sync.HybridCandidateRetriever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import com.feedback.config.RuntimeConfigService
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ClusterDecision(val clusterId: Long, val linkType: String, val similarity: BigDecimal, val reviewStatus: String)
@Service
class ClusteringEngine(
    private val repository: ClusterMutationRepository,
    private val profileFacts: ClusterProfileFactRepository,
    private val extractor: ProblemProfileExtractor,
    private val decisions: ClusterDecisionMaker,
    private val mapper: ObjectMapper,
    private val statistics: ClusterStatisticsService,
    private val businessAudits: BusinessAuditService,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val candidateRetrievers: ObjectProvider<HybridCandidateRetriever>,
    private val productScopes: ProductScopeResolver,
    private val channelContexts: ChannelClusterContextResolver,
    private val reviewGovernance:ClusterReviewGovernance,
    private val clusterAiDecisions: ClusterAiAssistedDecisionService? = null,
    private val runtimeConfig: RuntimeConfigService? = null
) {
    @Transactional
    fun cluster(sourceId: Long, reclassificationRunId: Long? = null): ClusterDecision? {
        repository.activeMainBySources(listOf(sourceId))[sourceId]?.let {
            return ClusterDecision(it.clusterId, it.linkType, it.matchSimilarity ?: BigDecimal.ZERO, it.reviewStatus)
        }
        val facts = profileFacts.load(listOf(sourceId))
        val content = facts.contents[sourceId] ?: return null
        if (content.maskingStatus != "PASS" || content.isDeleted) return null
        val source = facts.sources[sourceId] ?: return null
        // AI 分类只是可选上下文，永远不是准入条件。
        val ai = facts.ai[sourceId] ?: FeedbackAi(sourceId = sourceId, reviewStatus = "NOT_REQUIRED")
        var profile = extractor.extract(source, content, ai, productScopes.resolve(source), facts.contexts[sourceId]?.asMap().orEmpty())
        val topK=(runtimeConfig?.int("cluster.top-k",20)?:20).coerceIn(1,50)
        val recallFetchSize = (topK * 5).coerceAtMost(100)
        val recallResult = runCatching {
            candidateRetrievers.ifAvailable?.retrieve(profile.symptom, topK = recallFetchSize, excludeSourceId = sourceId)
                .orEmpty().let { recalls ->
                    val clusterIdsBySource = repository.activeMainBySources(recalls.map { it.sourceId }.toSet())
                        .mapValues { it.value.clusterId }
                    strongestRecallPerCluster(recalls, clusterIdsBySource) to (recalls.size >= recallFetchSize)
                }
        }.getOrDefault(emptyMap<Long, com.feedback.service.sync.HybridCandidate>() to false)
        val recallsByCluster = recallResult.first
        val derivedRecallLimitHit = recallResult.second
        val activeDays=(runtimeConfig?.int("cluster.active-lookback-days",90)?:90).coerceIn(1,3650)
        val resolvedDays=(runtimeConfig?.int("cluster.resolved-lookback-days",30)?:30).coerceIn(1,365)
        val crossModuleDictionary=runtimeConfig?.dictionary("cluster.cross-module-domains") ?: com.feedback.config.RuntimeStringDictionary.EMPTY
        val allowedCrossModules=crossModuleDictionary.keys()
            .filter{crossModuleDictionary[it]?.isNotBlank()==true}
            .map{it.split(':').map(String::uppercase).sorted().joinToString(":")}.toSet()
        val now=LocalDateTime.now()
        val signatureCandidates=profile.errorSignature?.let{
            repository.candidatesBySignature(profile.productScope, it)
        }.orEmpty()
        val exactCanonicalCandidates = repository.candidatesByExactProfile(
            profile.productScope, profile.functionModule, profile.issueCategory, profile.symptom
        )
        val exactFingerprintCandidates = repository.candidatesByFingerprint(profile.problemFingerprint)
        val exactCandidates = (exactFingerprintCandidates + exactCanonicalCandidates).distinctBy { it.id }
        val recalledClusters = if (recallsByCluster.isEmpty()) emptyList() else repository.clusters(recallsByCluster.keys)
        val recalledById = recalledClusters.associateBy { it.id }
        val orderedRecalledClusters = recallsByCluster.entries
            .sortedWith(compareByDescending<Map.Entry<Long, com.feedback.service.sync.HybridCandidate>> { it.value.fusedScore }
                .thenBy { it.key })
            .mapNotNull { recalledById[it.key] }
        // 精确的结构化召回不能依赖派生的搜索/向量索引，也不能依赖
        // 最近 100 条的回退。否则在积压爆发时，一个较旧的相同簇可能
        // 同时落出两个候选集，被拆成另一个 NEW_CLUSTER。
        val rawCandidates = (exactCandidates + signatureCandidates + orderedRecalledClusters +
            repository.candidatesByProduct(profile.productScope)).distinctBy{it.id}
        val strongRecallIds = (exactCandidates.map { it.id } + signatureCandidates.map { it.id }).toSet()
        val validClusterIds = eligibleClusterIds(rawCandidates.map { it.id })
        // 一次性载入被拒绝的候选。为每个候选都调一次 exists 查询，会让
        // Hibernate 反复自动刷写不断增长的再分类持久化上下文。
        val rejectedClusterIds = repository.rejectedClusterIds(sourceId)
        val eligibleCandidates = rawCandidates
            .filter { it.id in validClusterIds }
            .filter { cluster -> cluster.id in strongRecallIds ||
                when (cluster.clusterStatus) {
                    "ACTIVE", "MITIGATING", "REOPENED" -> cluster.updatedAt.isAfter(now.minusDays(activeDays.toLong()))
                    "RESOLVED" -> cluster.updatedAt.isAfter(now.minusDays(resolvedDays.toLong())) ||
                        (profile.errorSignature != null && profile.errorSignature == cluster.errorSignature)
                    else -> false
                }
            }
            .filter { cluster -> cluster.id in strongRecallIds ||
                cluster.id in recallsByCluster ||
                    (cluster.functionModule == profile.functionModule && cluster.issueCategory == profile.issueCategory) ||
                    (profile.errorSignature != null && profile.errorSignature == cluster.errorSignature)
            }.distinctBy { it.id }
            .filterNot { cluster -> cluster.id in rejectedClusterIds }
        val strongEligibleCount = eligibleCandidates.count { it.id in strongRecallIds }
        val weakEligibleCount = eligibleCandidates.count { it.id !in strongRecallIds }
        val boundedCandidates = eligibleCandidates.filter { it.id in strongRecallIds } +
            eligibleCandidates.filterNot { it.id in strongRecallIds }.take(topK)
        profile = profile.copy(recallDiagnostics = linkedMapOf(
            "fingerprint_candidate_count" to exactFingerprintCandidates.size,
            "legacy_canonical_candidate_count" to exactCanonicalCandidates.size,
            "exact_candidate_count" to exactCandidates.size,
            "duplicate_active_fingerprint_count" to
                (exactFingerprintCandidates.count { it.clusterStatus in setOf("ACTIVE", "MITIGATING", "REOPENED") } - 1).coerceAtLeast(0),
            "signature_candidate_count" to signatureCandidates.size,
            "strong_eligible_count" to strongEligibleCount,
            "weak_candidate_count" to weakEligibleCount,
            "weak_kept_count" to minOf(weakEligibleCount, topK),
            "weak_recall_truncated" to (weakEligibleCount > topK),
            "derived_recall_limit_hit" to derivedRecallLimitHit,
            "strong_recall_complete" to true
        ))
        val contexts = candidateContexts(boundedCandidates.map { it.id }, profile)
        val candidates = boundedCandidates
            .distinctBy { it.id }
            .map { cluster -> contextualize(profile,decisions.evaluate(profile, cluster, recallsByCluster[cluster.id]?.let { recall ->
                CandidateEvidence(cluster = cluster, lexicalScore = BigDecimal.ZERO, fusedScore = recall.fusedScore,
                    textRank = recall.textRank, vectorRank = recall.vectorRank, vectorScore = recall.vectorScore)
            },allowedCrossModules), contexts[cluster.id]) }
        val policy=ClusterDecisionPolicy(
            highThreshold=runtimeConfig?.decimal("cluster.high-threshold",BigDecimal("0.900000"))?:BigDecimal("0.900000"),
            reviewThreshold=runtimeConfig?.decimal("cluster.review-threshold",BigDecimal("0.750000"))?:BigDecimal("0.750000"),
            competitionMargin=runtimeConfig?.decimal("cluster.competition-margin",BigDecimal("0.030000"))?:BigDecimal("0.030000"),
            decisionVersion="cluster-decision-v3",configVersion=runtimeConfig?.string("cluster.config-version","cluster-config-v3")?:"cluster-config-v3",
            vectorHighThreshold=runtimeConfig?.decimal("cluster.vector-high-threshold",BigDecimal("0.900000"))?:BigDecimal("0.900000"),
            aiConfidenceReviewThreshold=runtimeConfig?.decimal(com.feedback.service.ai.AiReviewService.REVIEW_THRESHOLD_KEY,com.feedback.service.ai.AiReviewService.DEFAULT_REVIEW_THRESHOLD)
                ?:com.feedback.service.ai.AiReviewService.DEFAULT_REVIEW_THRESHOLD
        )
        // 完全相同的规范文本只是一个候选信号，它必须和其他匹配一样
        // 通过冲突、复发、竞争与风险这几道门禁。
        val deterministicPlan = decisions.decide(profile, candidates, policy)
        val aiResolution = clusterAiDecisions?.resolveOrEnqueue(sourceId, profile, deterministicPlan, candidates)
            ?: ClusterAiResolution(ClusterAiResolutionStatus.DISABLED)
        if (aiResolution.status == ClusterAiResolutionStatus.PENDING) return ClusterDecision(0, "AI_PENDING", BigDecimal.ZERO, "NOT_REQUIRED")
        val assistedPlan = applyAiPermissionModel(deterministicPlan, aiResolution, runtimeConfig?.decimal(
            "cluster.ai-auto-confidence-threshold", BigDecimal("0.850000")) ?: BigDecimal("0.850000"))
        val plan = enforceClusterNameCoverage(profile, assistedPlan)
        if (plan.action == "CANDIDATE") {
            // 模糊的确定性边界不能变成一道永久的人工闸门。把旧的候选行
            // 保留为可审计的 SKIPPED 事实，然后为这条安全记录
            // 保守地新建一条 MAIN 关联。
            val reason = "${plan.reason}；确定性边界不清，保守新建"
            return createOrLink(sourceId, source, ai, profile, policy,
                reclassificationRunId,
                ClusterDecisionPlan("NEW_CLUSTER", null, emptyList(), reason))
        }
        return createOrLink(sourceId, source, ai, profile, policy, reclassificationRunId, plan)
    }

    private fun createOrLink(sourceId:Long,source:com.feedback.model.feedback.FeedbackSource,ai:FeedbackAi,profile:ProblemProfile,
                             policy:ClusterDecisionPolicy,reclassificationRunId:Long?,plan:ClusterDecisionPlan):ClusterDecision?{
        // 调度器可能恰好在全量再分类运行开始之前选中了某个来源。
        // 因此在建 MAIN 链接之前立刻用加锁读再查一次，普通调用就无法
        // 把无主的关联写进那轮运行。再分类 Worker 则刻意带着
        // 它的运行 id 绕过这道防护。
        if (isNormalClusterWriteBlocked(reclassificationRunId)) return null
        val canonical = ProductPhenomenonFormatter.choose(
            null, profile.symptom, profile.functionModule, profile.issueCategory
        )
        val selected = plan.selected?.takeIf { sameHardProblemBoundary(it.cluster, profile) }
        val boundaryRejected = plan.selected != null && selected == null
        val resolved = selected?.cluster?.let { it to false } ?: createOrReuseCluster(canonical, profile)
        val cluster = repository.lockClusters(listOf(resolved.first.id)).single()
        // Cluster 先锁、同 source 的 link 再按 source/link ID 锁；并发 worker 在插入前重读 MAIN。
        val lockedLinks = repository.linksBySources(listOf(sourceId), lock = true)
        val existingMain = lockedLinks.singleOrNull { it.isActive && it.relationRole == "MAIN" }
        existingMain?.let { existing ->
            // 本事务刚创建的簇不能在并发 MAIN 已胜出后作为空簇提交；抛出以回滚整笔事务。
            if (resolved.second) throw ServiceException(ErrorCode.CLUSTER_MAIN_RACE, "CLUSTER_MAIN_CONCURRENTLY_CREATED")
            return ClusterDecision(existing.clusterId, existing.linkType,
                existing.matchSimilarity ?: BigDecimal.ZERO, existing.reviewStatus)
        }
        // Cluster 与 source link 均已按固定顺序锁定，才能关闭旧候选并写唯一 MAIN。
        skipActiveCandidates(lockedLinks.filter {
            it.isActive && it.relationRole == "CANDIDATE" && it.reviewStatus == "PENDING"
        }, plan.reason)
        val systemReopened = reopenResolvedClusterIfNeeded(cluster.id, cluster.lifecycleVersion, sourceId, canonical)
        val reusedByName = selected == null && !resolved.second
        val actualType = when {
            reusedByName -> "AUTO_HIGH"
            boundaryRejected -> "NEW_CLUSTER"
            else -> plan.action
        }
        val score = if (reusedByName) BigDecimal.ONE else selected?.semanticScore ?: BigDecimal.ZERO
        val decisionReason = when {
            systemReopened -> "同一主问题再次出现，复用原问题簇并由系统重新打开"
            reusedByName -> "规范簇名一致，复用唯一活动问题簇"
            boundaryRejected -> "候选簇与当前反馈存在硬问题边界冲突，保守新建"
            else -> plan.reason
        }
        val review = "NOT_REQUIRED"
        repository.insertLinks(listOf(ClusterLinkInsertPo(
            clusterId = cluster.id, feedbackSourceId = sourceId, relationRole="MAIN",linkType = actualType,
            matchSimilarity = score, matchEvidence = evidenceJson(selected), decisionVersion = policy.decisionVersion,
            decisionConfigVersion=policy.configVersion,featureSnapshot=profileJson(profile),reviewStatus = review,
            problemFingerprint=profile.problemFingerprint,
            decisionReason = decisionReason, reclassificationRunId = reclassificationRunId
        )))
        statistics.recomputeOne(cluster.id)
        val currentCluster = if (systemReopened) repository.lockClusters(listOf(cluster.id)).single() else cluster
        if (selected != null) evolveClusterName(currentCluster, profile.symptom, sourceId)
        val requestId = UUID.randomUUID().toString()
        businessAudits.append(BusinessAuditEvent(
            actor = null,
            operation = "CLUSTER_AUTO_MAIN_MUTATION",
            auditClass = "SYSTEM",
            outcome = "SUCCESS",
            targetType = "CLUSTER",
            targetId = cluster.id.toString(),
            requestId = requestId,
            clientIp = null,
            detail = mapOf(
                "sourceId" to sourceId,
                "requestId" to requestId,
                "expectedVersion" to null,
                "reason" to "AUTO_$actualType",
                "beforeClusterId" to null,
                "afterClusterId" to cluster.id,
                "affectedCount" to 1,
                "linkType" to actualType
            )
        ))
        dirtyMarker.mark(FeedbackListProjectionDirtyCommand(
            sourceIds = listOf(sourceId), reason = FeedbackListProjectionDirtyReason.CLUSTER
        ))
        return ClusterDecision(cluster.id, actualType, score, review)
    }

    /**
     * 已解决的问题仍然是其可见名称的身份持有者。同一个主问题
     * 再次出现时，要先把那个持有者重新打开，再挂接新的 MAIN 链接；
     * 否则新的反馈会悄悄挂在 RESOLVED 的簇下，或者
     * 竞态出第二个同名簇。
     */
    private fun reopenResolvedClusterIfNeeded(
        clusterId: Long,
        expectedVersion: Long,
        sourceId: Long,
        canonical: String
    ): Boolean {
        if (!repository.reopenResolved(clusterId, expectedVersion, "同一主问题收到新反馈，系统确定性重开")) return false
        val requestId = UUID.randomUUID().toString()
        businessAudits.append(BusinessAuditEvent(
            actor = null,
            operation = "CLUSTER_SYSTEM_REOPEN",
            auditClass = "SYSTEM",
            outcome = "SUCCESS",
            targetType = "CLUSTER",
            targetId = clusterId.toString(),
            requestId = requestId,
            clientIp = null,
            detail = mapOf(
                "clusterId" to clusterId,
                "recurrenceSourceId" to sourceId,
                "from" to "RESOLVED",
                "to" to "REOPENED",
                "reason" to "EXACT_PRIMARY_PROBLEM_RECURRENCE"
            )
        ))
        return true
    }

    /** MySQL 的唯一名称键加上 LAST_INSERT_ID，让首次建簇在竞态下安全。 */
    private fun createOrReuseCluster(canonical: String, profile: ProblemProfile, collisionAttempt: Int = 0): Pair<Cluster, Boolean> {
        ensureClusterInvariant(collisionAttempt <= 3) { "CLUSTER_NAME_BOUNDARY_CONVERGENCE_EXHAUSTED" }
        var effectiveCanonical = canonical
        var nameKey = stableClusterNameKey(effectiveCanonical)
        repository.byNameKeys(listOf(nameKey)).firstOrNull()?.let { existing ->
            if (sameHardProblemBoundary(existing, profile)) return existing to false
            effectiveCanonical = qualifyConflictingClusterName(canonical, profile)
            nameKey = stableClusterNameKey(effectiveCanonical)
            repository.byNameKeys(listOf(nameKey)).firstOrNull()?.let { qualified ->
                if (sameHardProblemBoundary(qualified, profile)) return qualified to false
                val suffix = profile.problemFingerprint.take(8)
                effectiveCanonical = "${effectiveCanonical.take(188)}-$suffix"
                nameKey = stableClusterNameKey(effectiveCanonical)
            }
        }
        val result = repository.insertOrReuse(ClusterInsertPo(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = effectiveCanonical, clusterNameKey = nameKey,
            productScope = profile.productScope, functionModule = profile.functionModule,
            issueCategory = profile.issueCategory, canonicalSymptom = effectiveCanonical,
            canonicalObject = profile.targetObject, canonicalTrigger = profile.trigger,
            errorSignature = profile.errorSignature
        ))
        val persisted = result.first
        if (result.second) return persisted to true
        if (!sameHardProblemBoundary(persisted, profile)) {
            val suffix = profile.problemFingerprint.take(8)
            val nextName = if (collisionAttempt == 0) qualifyConflictingClusterName(canonical, profile)
                else "${canonical.take(186)}-$suffix-${collisionAttempt + 1}"
            return createOrReuseCluster(nextName, profile, collisionAttempt + 1)
        }
        return persisted to false
    }

    private fun qualifyConflictingClusterName(canonical: String, profile: ProblemProfile): String {
        val boundary = listOfNotNull(
            profile.targetObject,
            when (ClusterProblemOntology.operationClass(profile.trigger)) {
                "CLICK" -> "点击操作"
                "START" -> "启动阶段"
                "OPEN" -> "打开阶段"
                "ENTER" -> "进入页面"
                "PLAY" -> "播放阶段"
                "SUBMIT" -> "提交阶段"
                "UPLOAD" -> "上传阶段"
                "DOWNLOAD" -> "下载阶段"
                else -> null
            },
            profile.functionModule?.let(::humanFunctionModule),
            profile.errorSignature
        ).distinct().joinToString("·").ifBlank { profile.productScope }
        val suffix = "（$boundary）"
        return canonical.take((200 - suffix.length).coerceAtLeast(1)) + suffix
    }

    private fun humanFunctionModule(value: String): String = when (value.uppercase()) {
        "AI_TUTOR" -> "AI助教"
        "VIDEO" -> "视频功能"
        "CLASSROOM" -> "课堂功能"
        "HOMEWORK" -> "作业功能"
        else -> value.replace('_', ' ').trim()
    }

    internal fun isNormalClusterWriteBlocked(reclassificationRunId: Long?): Boolean {
        if (reclassificationRunId != null) return false
        return repository.hasActiveReclassification()
    }

    private fun skipActiveCandidates(candidates: List<com.feedback.model.cluster.ClusterLink>, reason: String) {
        repository.skipCandidates(candidates.map { ClusterCandidateSkipPo(it.id, it.reviewVersion) }, reason.take(500))
    }

    private fun evidenceJson(evidence:CandidateEvidence?)=mapper.writeValueAsString(evidence?.let { mapOf(
        "core_matches" to it.coreMatches,"conflicts" to it.conflicts,
        "semantic_score" to it.semanticScore,"ranking_score" to it.rankingScore,"text_score" to it.textScore,
        "version_match" to it.versionMatch,"time_distance_hours" to it.timeDistanceHours,"cross_channel" to it.crossChannelEvidence,
        "rrf_score" to it.fusedScore,"text_rank" to it.textRank,"vector_rank" to it.vectorRank,
        "vector_score" to it.vectorScore,"historical_recurrence" to it.historicalRecurrence,
        "normalized_text_length" to it.normalizedTextLength,"ontology_version" to ClusterProblemOntology.VERSION
    ) }?:emptyMap<String,Any?>())

    private data class CandidateContext(val latest: LocalDateTime?, val versionMatch: Boolean?, val crossChannel: Boolean)

    private fun contextualize(profile:ProblemProfile,evidence:CandidateEvidence,context: CandidateContext?):CandidateEvidence{
        val latest=context?.latest
        val hours=latest?.let{kotlin.math.abs(java.time.Duration.between(it,profile.sourceEventTime).toHours())}
        val versionMatch=context?.versionMatch
        val cross=context?.crossChannel ?: false
        var score=evidence.semanticScore
        if(versionMatch==true)score+=BigDecimal("0.050000")
        if(hours!=null&&hours<=168)score+=BigDecimal("0.030000")
        if("FUNCTION_MODULE" in evidence.coreMatches)score+=BigDecimal("0.020000")
        return evidence.copy(rankingScore=score.min(BigDecimal.ONE).setScale(6,java.math.RoundingMode.HALF_UP),versionMatch=versionMatch,
            timeDistanceHours=hours,crossChannelEvidence=cross)
    }

    private fun eligibleClusterIds(clusterIds: Collection<Long>): Set<Long> {
        if (clusterIds.isEmpty()) return emptySet()
        val eligible = linkedSetOf<Long>()
        clusterIds.distinct().sorted().chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach { chunk ->
            repository.visitActiveMainPages(chunk) { page ->
                val facts = profileFacts.load(page.map { it.feedbackSourceId })
                page.forEach { link ->
                    val content = facts.contents[link.feedbackSourceId]
                    if (content != null && content.maskingStatus == "PASS" && !content.isDeleted) eligible += link.clusterId
                }
            }
        }
        return eligible
    }

    private fun candidateContexts(clusterIds: Collection<Long>, profile: ProblemProfile): Map<Long, CandidateContext> {
        if (clusterIds.isEmpty()) return emptyMap()
        data class Acc(var latest: LocalDateTime? = null, var version: Boolean = false, var cross: Boolean = false)
        val acc = clusterIds.distinct().associateWith { Acc() }.toMutableMap()
        clusterIds.distinct().sorted().chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach { chunk ->
            repository.visitActiveMainPages(chunk) { page ->
                val facts = profileFacts.load(page.map { it.feedbackSourceId })
                page.forEach { link ->
                    val source = facts.sources[link.feedbackSourceId]
                        ?: clusterInvariantViolation("CLUSTER_CONTEXT_SOURCE_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
                    val current = acc.getValue(link.clusterId)
                    val occurredAt = source.sourceEventTime ?: source.ingestTime
                    if (current.latest == null || occurredAt.isAfter(current.latest)) current.latest = occurredAt
                    if (profile.appVersion != null && source.appVersion == profile.appVersion) current.version = true
                    if (source.sourceChannel != profile.sourceChannel) current.cross = true
                }
            }
        }
        return acc.mapValues { (_, value) -> CandidateContext(
            latest = value.latest,
            versionMatch = profile.appVersion?.let { value.version },
            crossChannel = value.cross
        ) }
    }

    private fun evolveClusterName(cluster: Cluster, incomingSymptom: String, sourceId: Long) {
        val previous = cluster.canonicalSymptom ?: cluster.clusterName
        val evolved = ProductPhenomenonFormatter.evolve(previous, incomingSymptom)
            ?: return
        val evolvedKey = stableClusterNameKey(evolved)
        val collision = repository.byNameKeys(listOf(evolvedKey)).firstOrNull()
        if (collision != null && collision.id != cluster.id) return
        if (repository.evolveName(cluster.id, cluster.lifecycleVersion, cluster.clusterName, evolved, evolvedKey)) {
            repository.appendNameEvolution(sourceId, cluster.id, previous, evolved)
        }
    }

    private fun profileJson(profile:ProblemProfile)=mapper.writeValueAsString(profile)

    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    companion object {
        internal val AI_DOWNSTREAM_ELIGIBLE_STATUSES = setOf("NOT_REQUIRED", "CONFIRMED", "CORRECTED")
    }
}

internal fun sameHardProblemBoundary(cluster: Cluster, profile: ProblemProfile): Boolean {
    // 产品/应用/模块/类别属于影响面维度，不是问题身份。
    val clusterTarget = ClusterProblemOntology.targetClass(cluster.canonicalObject)
    val profileTarget = ClusterProblemOntology.targetClass(profile.targetObject)
    if (clusterTarget != null && profileTarget != null && clusterTarget != profileTarget) return false
    val clusterAction = ClusterProblemOntology.operationClass(cluster.canonicalTrigger)
    val profileAction = ClusterProblemOntology.operationClass(profile.trigger)
    if (clusterAction != null && profileAction != null && clusterAction != profileAction) return false
    val clusterModes = ClusterProblemOntology.failureModes(cluster.canonicalSymptom.orEmpty())
    val causalSubmission = clusterModes + profile.failureModes == setOf("NO_RESPONSE", "SUBMIT")
    if (clusterModes.isNotEmpty() && profile.failureModes.isNotEmpty() &&
        clusterModes != profile.failureModes && !causalSubmission) return false
    val clusterSignature = cluster.errorSignature?.takeIf { it.isNotBlank() }
    val profileSignature = profile.errorSignature?.takeIf { it.isNotBlank() }
    if (clusterSignature != null && profileSignature != null && clusterSignature != profileSignature) return false
    return true
}

internal fun clusterNameCoversProfile(name: String, profile: ProblemProfile): Boolean {
    if (!ProductPhenomenonFormatter.isConcretePhenomenon(name)) return false
    val nameModes = ClusterProblemOntology.failureModes(name)
    val causalSubmission = nameModes + profile.failureModes == setOf("NO_RESPONSE", "SUBMIT")
    if (profile.failureModes.isNotEmpty() && nameModes != profile.failureModes && !causalSubmission) return false
    if (profile.targetObject != null && !name.contains(profile.targetObject, ignoreCase = true)) return false
    if (profile.scenarioOrAction != null &&
        ClusterProblemOntology.operationClass(name) != profile.scenarioOrAction) return false
    if (profile.failureModes.isEmpty() && profile.targetObject == null && profile.scenarioOrAction == null &&
        normalizeClusterSimilarityText(name) != normalizeClusterSimilarityText(profile.symptom) &&
        ProductPhenomenonFormatter.evolve(name, profile.symptom) == null &&
        ProductPhenomenonFormatter.evolve(profile.symptom, name) == null) return false
    return true
}

internal fun enforceClusterNameCoverage(
    profile: ProblemProfile,
    plan: ClusterDecisionPlan
): ClusterDecisionPlan {
    val evidence = plan.selected ?: return plan
    val current = evidence.cluster.canonicalSymptom ?: evidence.cluster.clusterName
    if (normalizeClusterSimilarityText(current) == normalizeClusterSimilarityText(profile.symptom)) return plan
    if (ProductPhenomenonFormatter.evolve(current, profile.symptom) != null) return plan
    return ClusterDecisionPlan(
        "NEW_CLUSTER", null, emptyList(),
        "已有簇名称无法安全覆盖新成员，保守新建簇"
    )
}

internal fun applyAiPermissionModel(
    deterministic: ClusterDecisionPlan,
    resolution: ClusterAiResolution,
    threshold: BigDecimal
): ClusterDecisionPlan {
    val advice = resolution.advice ?: return deterministic
    if (advice.confidence < threshold) return deterministic
    return when (deterministic.action) {
        "AUTO_HIGH" -> when (advice.recommendedAction) {
            "CREATE_NEW" -> ClusterDecisionPlan(
                "NEW_CLUSTER", null, emptyList(), "AI建议降级；程序保守新建：${advice.explanation}".take(500)
            )
            "LINK_EXISTING" -> {
                val selectedId = advice.selectedClusterRef?.removePrefix("cluster:")?.toLongOrNull()
                if (selectedId == deterministic.selected?.cluster?.id) deterministic.copy(
                    reason = "${deterministic.reason}；AI仅确认同一确定性候选".take(500)
                ) else deterministic
            }
            else -> deterministic
        }
        // AI 不能把确定性的「新建/模糊」边界升级成自动关联。
        "NEW_CLUSTER", "CANDIDATE" -> deterministic
        else -> deterministic
    }
}

internal fun strongestRecallPerCluster(
    recalls: List<com.feedback.service.sync.HybridCandidate>,
    clusterIdsBySource: Map<Long, Long>
): Map<Long, com.feedback.service.sync.HybridCandidate> = recalls
    .mapNotNull { recall -> clusterIdsBySource[recall.sourceId]?.let { it to recall } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, candidates) ->
        candidates.sortedWith(
            compareByDescending<com.feedback.service.sync.HybridCandidate> { it.fusedScore }
                .thenBy { it.sourceId }
        ).first()
    }

@Component
class ClusteringScheduler(
    private val repository: ClusterMutationRepository,
    private val profileFacts: ClusterProfileFactRepository,
    private val engine: ClusteringEngine,
    @Value("\${app.ai.cluster-assisted-decision-enabled:false}")
    private val aiAssistedDecisionEnabled: Boolean = false
) {
    private val cursor = AtomicLong(0)

    fun clusterReadyRecords() {
        if (repository.hasActiveReclassification()) return
        var ids = profileFacts.eligibleAfter(cursor.get(), BATCH_SIZE)
        if (ids.isEmpty() && cursor.get() != 0L) {
            cursor.set(0)
            ids = profileFacts.eligibleAfter(0, BATCH_SIZE)
        }
        if (ids.isEmpty()) return
        cursor.set(ids.last())
        val alreadyLinked = repository.activeMainBySources(ids).keys
        val aiPending = if (aiAssistedDecisionEnabled) repository.pendingAiSourceIds(ids) else emptySet()
        ids.asSequence()
            .filterNot { it in alreadyLinked || it in aiPending }
            .take(BATCH_SIZE)
            .forEach { sourceId ->
                try {
                    engine.cluster(sourceId)
                } catch (error: ServiceException) {
                    if (error.errorCode != ErrorCode.CLUSTER_MAIN_RACE) throw error
                    // 事务已回滚；游标循环会再次看到 source，当前批继续处理其他记录。
                }
            }
    }

    companion object { const val BATCH_SIZE = 100 }
}
