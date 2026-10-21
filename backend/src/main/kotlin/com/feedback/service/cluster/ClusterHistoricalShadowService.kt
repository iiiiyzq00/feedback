package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentitySafeTextRepository
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.model.applicationidentity.ApplicationResolutionStatus
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.model.cluster.ClusterLink
import com.feedback.manager.applicationidentity.ApplicationIdentityResolver
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatch
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.model.applicationidentity.ApplicationResolution
import com.feedback.service.sync.EmbeddingProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class HistoricalApplicationFact(
    val source: ApplicationIdentitySourceInput,
    val applicationKey: String?,
    val applicationName: String?,
    val ambiguous: Boolean
)

data class HistoricalClusterShadowInput(
    val classificationInput: ClusterClassificationInput,
    val currentClusterId: Long?
)

data class HistoricalClusterShadowBatch(
    val records: List<HistoricalClusterShadowInput>,
    val nextSourceId: Long,
    val mayHaveMore: Boolean
)

data class HistoricalShadowAssignment(
    val sourceId: Long,
    val currentClusterId: Long?,
    val status: ProblemClassificationStatus,
    val proposedClusterKey: String?,
    val proposedClusterName: String?,
    val decision: String,
    val score: BigDecimal?,
    val reason: String,
    val manualLocked: Boolean
)

data class HistoricalClusterShadowReport(
    val algorithmVersion: String,
    val processedCount: Int,
    val currentClusterCount: Int,
    val proposedClusterCount: Int,
    val actionableCount: Int,
    val nonActionableCount: Int,
    val memberCountByChannel: Map<String, Int>,
    val actionableCountByChannel: Map<String, Int>,
    val pairwiseChangedCount: Int,
    val pairwiseMergedCount: Int,
    val pairwiseSplitCount: Int,
    val nextSourceId: Long,
    val mayHaveMore: Boolean,
    val assignments: List<HistoricalShadowAssignment>
)

fun interface HistoricalApplicationFactPort {
    fun read(afterSourceId: Long, limit: Int): List<HistoricalApplicationFact>
}

/** 只解析已有应用身份；未获批的别名不会在只读影子批次中自动建档或跨渠道合并。 */
@Service
class HistoricalApplicationFactReader(
    private val sources: ApplicationIdentitySourceRepository,
    private val identities: ApplicationIdentityRepository,
    private val resolver: ApplicationIdentityResolver,
    private val primaryFacts: PrimaryProblemFactRepository,
    private val safeTexts: ApplicationIdentitySafeTextRepository,
    private val textMatcher: CanonicalApplicationTextMatcher
) : HistoricalApplicationFactPort {
    override fun read(afterSourceId: Long, limit: Int): List<HistoricalApplicationFact> {
        val inputs = sources.nextFourChannelInputs(afterSourceId, limit)
        val keysBySource = inputs.associate { source ->
            source.sourceId to source.aliases.mapNotNull { it.normalized() }.toSet()
        }
        val keys = keysBySource.values.flatten().distinct()
        val activeAliases = identities.activeAliases(keys)
        val approvedFacts = primaryFacts.findBySourceIds(inputs.map(ApplicationIdentitySourceInput::sourceId))
        val textEligible = inputs.filter { it.sourceChannel in TEXT_EVIDENCE_CHANNELS }
        val passingTexts = safeTexts.findPassingBySourceIds(textEligible.map { it.sourceId })
        val canonicalIdentities = if (textEligible.isEmpty()) emptyList() else canonicalIdentities()
        return inputs.map { source ->
            val sourceKeys = keysBySource.getValue(source.sourceId)
            var priorAmbiguity = false
            when (val latest = resolver.resolve(source, activeAliases.filterKeys(sourceKeys::contains))) {
                is ApplicationResolution.Resolved -> return@map HistoricalApplicationFact(
                    source, latest.identity.publicId, latest.identity.displayName, false
                )
                is ApplicationResolution.Ambiguous -> {
                    if (source.sourceChannel !in TEXT_EVIDENCE_CHANNELS) {
                        return@map HistoricalApplicationFact(source, null, null, true)
                    }
                    // 历史/别名带来的歧义，不足以压掉后面在脱敏 PASS 文本里
                    // 唯一出现的当前规范应用。
                    priorAmbiguity = true
                }
                is ApplicationResolution.NewIdentity,
                ApplicationResolution.Unresolved -> Unit
            }
            val persisted = approvedFacts[source.sourceId]?.let { fact ->
                val status = fact.applicationResolutionStatus
                when (status) {
                    ApplicationResolutionStatus.RESOLVED -> {
                        ensureClusterInvariant(fact.applicationIdentityId != null &&
                            !fact.applicationPublicIdSnapshot.isNullOrBlank() &&
                            fact.applicationMappingVersion != null && fact.applicationMappingVersion > 0 &&
                            fact.resolutionReasonCode == null) {
                            "APPLICATION_RESOLVED_FACT_INCOMPLETE"
                        }
                        HistoricalApplicationFact(
                            source,
                            fact.applicationPublicIdSnapshot,
                            fact.applicationDisplayNameSnapshot,
                            false
                        )
                    }
                    ApplicationResolutionStatus.AMBIGUOUS -> if (source.sourceChannel !in TEXT_EVIDENCE_CHANNELS) {
                        HistoricalApplicationFact(source, null, null, true)
                    } else {
                        priorAmbiguity = true
                        null
                    }
                    ApplicationResolutionStatus.UNRESOLVED -> if (source.sourceChannel !in TEXT_EVIDENCE_CHANNELS) {
                        HistoricalApplicationFact(source, null, null, false)
                    } else null
                }
            }
            if (persisted != null) return@map persisted
            if (source.sourceChannel !in TEXT_EVIDENCE_CHANNELS) {
                return@map HistoricalApplicationFact(source, null, null, false)
            }
            when (val match = textMatcher.match(passingTexts[source.sourceId], canonicalIdentities)) {
                is CanonicalApplicationTextMatch.Resolved -> HistoricalApplicationFact(
                    source, match.identity.publicId, match.identity.displayName, false
                )
                is CanonicalApplicationTextMatch.Ambiguous -> HistoricalApplicationFact(source, null, null, true)
                CanonicalApplicationTextMatch.Unresolved ->
                    HistoricalApplicationFact(source, null, null, priorAmbiguity)
            }
        }
    }

    private fun canonicalIdentities(): List<ApplicationIdentitySnapshot> {
        val result = mutableListOf<ApplicationIdentitySnapshot>()
        var cursor: String? = null
        repeat(MAX_IDENTITY_PAGES) {
            val page = identities.listAfter(cursor, IDENTITY_PAGE_SIZE)
            result += page
            if (page.size < IDENTITY_PAGE_SIZE) return result
            cursor = page.last().publicId
        }
        clusterInvariantViolation("APPLICATION_TEXT_IDENTITY_SCOPE_EXCEEDED")
    }

    companion object {
        private const val IDENTITY_PAGE_SIZE = 100
        private const val MAX_IDENTITY_PAGES = 10
        private val TEXT_EVIDENCE_CHANNELS = setOf(
            ApplicationSourceChannel.JIRA,
            ApplicationSourceChannel.QUALITY,
            ApplicationSourceChannel.REDBLACK
        )
    }
}

fun interface HistoricalClusterShadowInputPort {
    fun read(afterSourceId: Long, limit: Int): HistoricalClusterShadowBatch
}

/**
 * 真实历史输入只在一个只读短事务中按表批量加载。事务提交后，协调器才执行 CPU 密集的影子算法，
 * 避免把文本处理、候选计算或未来可能增加的外部辅助能力放进数据库事务。
 */
@Service
class HistoricalClusterShadowInputReader(
    private val applications: HistoricalApplicationFactPort,
    private val profileFacts: ClusterProfileFactRepository,
    private val clusters: ClusterMutationRepository
) : HistoricalClusterShadowInputPort {
    @Transactional(readOnly = true)
    override fun read(afterSourceId: Long, limit: Int): HistoricalClusterShadowBatch {
        ensureClusterRequest(afterSourceId >= 0) { "CLUSTER_SHADOW_CURSOR_INVALID" }
        ensureClusterRequest(limit in 1..MAX_BATCH_SIZE) { "CLUSTER_SHADOW_BATCH_INVALID" }
        val applicationFacts = applications.read(afterSourceId, limit)
        if (applicationFacts.isEmpty()) return HistoricalClusterShadowBatch(emptyList(), afterSourceId, false)
        val ids = applicationFacts.map { it.source.sourceId }
        val facts = profileFacts.loadShadowSafe(ids)
        val links = clusters.activeMainBySources(ids)
        val clusterFacts = clusters.clusters(links.values.map { it.clusterId }.distinct()).associateBy { it.id }
        val records = applicationFacts.map { application ->
            val sourceId = application.source.sourceId
            val source = facts.sources[sourceId]
                ?: clusterInvariantViolation("CLUSTER_SHADOW_SOURCE_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
            val content = facts.contents[sourceId]
                ?: clusterInvariantViolation("CLUSTER_SHADOW_CONTENT_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
            val disposition = when {
                content.isDeleted -> ShadowContentDisposition.INELIGIBLE
                content.maskingStatus == "PASS" -> ShadowContentDisposition.ELIGIBLE
                content.maskingStatus == "REVIEW_NEEDED" -> ShadowContentDisposition.MASKING_REVIEW_REQUIRED
                else -> ShadowContentDisposition.INELIGIBLE
            }
            val current = links[sourceId]
            val manual = current?.takeIf { disposition == ShadowContentDisposition.ELIGIBLE }
                ?.takeIf(ClusterLink::isHumanLockedMain)?.let { link ->
                val cluster = clusterFacts[link.clusterId]
                    ?: clusterInvariantViolation("CLUSTER_SHADOW_MANUAL_CLUSTER_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
                ManualClusterConclusion("manual:${cluster.id}", cluster.clusterName)
            }
            HistoricalClusterShadowInput(
                ClusterClassificationInput(
                    sourceId = sourceId,
                    applicationKey = application.applicationKey,
                    applicationName = application.applicationName,
                    title = content.title,
                    description = content.description,
                    functionModule = content.functionModule,
                    issueCategory = content.issueCategory,
                    applicationAmbiguous = application.ambiguous,
                    contentDisposition = disposition,
                    manualConclusion = manual,
                    sourceChannel = source.sourceChannel
                ),
                currentClusterId = current?.clusterId
            )
        }
        return HistoricalClusterShadowBatch(
            records = records,
            nextSourceId = ids.last(),
            mayHaveMore = applicationFacts.size == limit
        )
    }

    companion object { const val MAX_BATCH_SIZE = 100 }
}

/** 默认关闭的真实只读影子协调器；本类没有事务，也不保存或切换任何 generation。 */
@Service
class HistoricalClusterShadowService(
    private val input: HistoricalClusterShadowInputPort,
    @Value("\${app.cluster.classification-v2-shadow-enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${app.cluster.semantic-shadow-enabled:false}")
    private val semanticEnabled: Boolean = false,
    private val embeddingProvider: ObjectProvider<EmbeddingProvider>? = null
) {
    private val embeddings = if (semanticEnabled) embeddingProvider?.ifAvailable else null
    private val semanticOperational = semanticEnabled && embeddings != null &&
        !embeddings.modelVersion.startsWith("hashing", ignoreCase = true)
    private val classifier = ClusterShadowClassifierV3(
        recall = if (semanticOperational) SemanticCandidateRecallV3(EmbeddingProviderSemanticPort(embeddings!!))
        else ClusterCandidateRecallV2()
    )

    fun evaluate(afterSourceId: Long, limit: Int): HistoricalClusterShadowReport {
        if (!enabled) throw ServiceException(
            ErrorCode.CLUSTER_SHADOW_EVALUATION_DISABLED,
            "CLUSTER_CLASSIFICATION_V2_SHADOW_DISABLED"
        )
        ensureClusterRequest(afterSourceId >= 0) { "CLUSTER_SHADOW_CURSOR_INVALID" }
        ensureClusterRequest(limit in 1..HistoricalClusterShadowInputReader.MAX_BATCH_SIZE) { "CLUSTER_SHADOW_BATCH_INVALID" }
        val batch = input.read(afterSourceId, limit)
        val proposed = classifier.classify(batch.records.map { it.classificationInput })
        val currentBySource = batch.records.associate { it.classificationInput.sourceId to it.currentClusterId }
        val proposedBySource = proposed.assignments.associateBy(ShadowClusterAssignment::sourceId)
        val channelBySource = batch.records.associate {
            it.classificationInput.sourceId to (it.classificationInput.sourceChannel ?: "UNKNOWN")
        }
        val actionableIds = proposed.assignments.filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .mapTo(hashSetOf(), ShadowClusterAssignment::sourceId)
        val pairwise = pairwiseChanges(currentBySource, proposedBySource)
        return HistoricalClusterShadowReport(
            algorithmVersion = AtomicProblemExtractorV3.VERSION + if (semanticOperational) "+semantic" else "",
            processedCount = batch.records.size,
            currentClusterCount = currentBySource.values.filterNotNull().toSet().size,
            proposedClusterCount = proposed.clusterCount,
            actionableCount = actionableIds.size,
            nonActionableCount = proposed.nonActionableCount,
            memberCountByChannel = channelBySource.values.groupingBy { it }.eachCount().toSortedMap(),
            actionableCountByChannel = channelBySource.filterKeys(actionableIds::contains).values
                .groupingBy { it }.eachCount().toSortedMap(),
            pairwiseChangedCount = pairwise.merged + pairwise.split,
            pairwiseMergedCount = pairwise.merged,
            pairwiseSplitCount = pairwise.split,
            nextSourceId = batch.nextSourceId,
            mayHaveMore = batch.mayHaveMore,
            assignments = batch.records.map { record ->
                val assignment = proposedBySource.getValue(record.classificationInput.sourceId)
                HistoricalShadowAssignment(
                    assignment.sourceId, record.currentClusterId, assignment.status, assignment.clusterKey,
                    assignment.clusterName, assignment.decision, assignment.score, assignment.reason,
                    assignment.manualLocked
                )
            }
        )
    }

    private fun pairwiseChanges(
        current: Map<Long, Long?>,
        proposed: Map<Long, ShadowClusterAssignment>
    ): PairwiseChanges {
        val ids = current.keys.sorted()
        var merged = 0
        var split = 0
        for (leftIndex in ids.indices) for (rightIndex in leftIndex + 1 until ids.size) {
            val left = ids[leftIndex]
            val right = ids[rightIndex]
            val currentSame = current[left] != null && current[left] == current[right]
            val proposedSame = proposed.getValue(left).clusterKey != null &&
                proposed.getValue(left).clusterKey == proposed.getValue(right).clusterKey
            if (!currentSame && proposedSame) merged++
            if (currentSame && !proposedSame) split++
        }
        return PairwiseChanges(merged, split)
    }

    private data class PairwiseChanges(val merged: Int, val split: Int)
}

/**
 * 历史数据可能来自不同时期的审核流程：新数据有审核人，旧数据可能只保留确认状态或 MANUAL 类型。
 * 影子评测对任一人工证据都失败关闭，宁可多冻结也不允许自动结果覆盖人工结论。
 */
internal fun ClusterLink.isHumanLockedMain(): Boolean =
    reviewedBy != null || reviewStatus == "CONFIRMED" || linkType.startsWith("MANUAL_")
