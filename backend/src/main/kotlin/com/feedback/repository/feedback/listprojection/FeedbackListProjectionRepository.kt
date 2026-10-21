package com.feedback.repository.feedback.listprojection

import com.feedback.model.po.problem.analysis.ClusterFactsPo
import com.feedback.model.po.feedback.listprojection.*
import org.springframework.stereotype.Repository

data class FeedbackListProjectionAuxiliaryFacts(
    val aiBySourceId: Map<Long, FeedbackListAiFactsPo>,
    val workflowBySourceId: Map<Long, FeedbackListWorkflowFactsPo>,
    val assignmentBySourceId: Map<Long, FeedbackListAssignmentFactsPo>,
    val handoffBySourceId: Map<Long, FeedbackListHandoffFactsPo>,
    val attachmentBySourceId: Map<Long, FeedbackListAttachmentFactsPo>,
    val importBatchById: Map<Long, FeedbackListImportBatchFactsPo>,
    val importRowsBySourceId: Map<Long, List<FeedbackListImportRowFactsPo>>,
    val clusterLinkBySourceId: Map<Long, FeedbackListClusterLinkFactsPo>,
    val identityTypesBySourceId: Map<Long, List<FeedbackListIdentityTypeFactsPo>>,
    val channelBySourceId: Map<Long, FeedbackListChannelFactsPo>,
    val clusterById: Map<Long, ClusterFactsPo> = emptyMap(),
    val identityFingerprintsBySourceId: Map<Long, List<FeedbackListIdentityFingerprintFactsPo>> = emptyMap(),
    val clusterDecisionBySourceId: Map<Long, FeedbackListClusterDecisionFactsPo> = emptyMap(),
    val applicationDictionary: List<String> = emptyList()
)

data class FeedbackListProjectionVerifiedFacts(
    val mutationEpoch: Long,
    val projectedCount: Long,
    val rejectedCount: Long,
    val contentChecksum: String,
    val memberChecksum: String,
    val analysisChecksum: String = "0".repeat(64),
    val analysisIdentityChecksum: String = "0".repeat(64),
    val analysisIdentityCount: Long = 0
)

/**
 * 反馈列表投影的稳定用例数据边界。
 *
 * 具体 Mapper 已按 generation、投影存储、来源、流程、导入、归簇、渠道和字典拆入窄 Repository；
 * 本门面只保持既有 Service API，并组合一次投影构建所需的跨表事实。
 */
@Repository
class FeedbackListProjectionRepository(
    private val generations: FeedbackListProjectionGenerationRepository,
    private val store: FeedbackListProjectionStoreRepository,
    private val sourceFacts: FeedbackListProjectionSourceFactsRepository,
    private val workflowFacts: FeedbackListProjectionAuxiliaryWorkflowRepository,
    private val importFacts: FeedbackListProjectionAuxiliaryImportRepository,
    private val clusterFacts: FeedbackListProjectionAuxiliaryClusterRepository,
    private val channelFacts: FeedbackListProjectionAuxiliaryChannelRepository,
    private val dictionary: FeedbackListProjectionDictionaryRepository
) {
    fun createBuilding(generationKey: String, buildFenceSourceId: Long, expectedSourceCount: Long, createdBy: Long) =
        generations.createBuilding(generationKey, buildFenceSourceId, expectedSourceCount, createdBy)
    fun sourceFence() = sourceFacts.sourceFence()
    fun sourceCountThroughFence(fenceSourceId: Long) = sourceFacts.sourceCountThroughFence(fenceSourceId)
    fun nextSourceIds(afterSourceId: Long, fenceSourceId: Long, limit: Int) =
        sourceFacts.nextSourceIds(afterSourceId, fenceSourceId, limit)
    fun lockByKey(generationKey: String) = generations.lockByKey(generationKey)
    fun lockManagedGenerations() = generations.lockManagedGenerations()
    fun markVerified(generationId: Long, epoch: Long, projectedCount: Long, rejectedCount: Long,
                     contentChecksum: String, memberChecksum: String, analysisChecksum: String = "0".repeat(64),
                     analysisIdentityChecksum: String = "0".repeat(64), analysisIdentityCount: Long = 0) =
        generations.markVerified(generationId, epoch, projectedCount, rejectedCount, contentChecksum, memberChecksum,
            analysisChecksum, analysisIdentityChecksum, analysisIdentityCount)
    fun extendBuildFence(generationId: Long, expectedFenceSourceId: Long, expectedSourceCount: Long,
                         nextFenceSourceId: Long, nextSourceCount: Long) =
        generations.extendBuildFence(generationId, expectedFenceSourceId, expectedSourceCount, nextFenceSourceId, nextSourceCount)
    fun prepareActiveForRollback(generationId: Long, fenceSourceId: Long, baselineCount: Long,
                                 facts: FeedbackListProjectionVerifiedFacts) =
        generations.prepareActiveForRollback(generationId, fenceSourceId, baselineCount, facts)
    fun transition(generationId: Long, expectedStatus: String, nextStatus: String, actorId: Long? = null,
                   cutoverEpoch: Long? = null, failureCode: String? = null, failureSummary: String? = null) =
        generations.transition(generationId, expectedStatus, nextStatus, actorId, cutoverEpoch, failureCode, failureSummary)
    fun projectionPage(generationId: Long, afterSourceId: Long, limit: Int) =
        store.projectionPage(generationId, afterSourceId, limit)
    fun memberPage(generationId: Long, afterSourceId: Long, limit: Int) = store.memberPage(generationId, afterSourceId, limit)
    fun identityPage(generationId: Long, afterSourceId: Long, afterIdentityKey: String, limit: Int) =
        store.identityPage(generationId, afterSourceId, afterIdentityKey, limit)
    fun lockBuilding(generationId: Long) = generations.lockBuilding(generationId)
    fun lockWritableGenerations() = generations.lockWritableGenerations()
    fun sources(sourceIds: List<Long>) = sourceFacts.sources(sourceIds)
    fun contents(sourceIds: List<Long>) = sourceFacts.contents(sourceIds)
    fun owners(ownerIds: List<Long>) = sourceFacts.owners(ownerIds)

    /** 仅允许为已经通过安全资格判断的来源加载辅助事实。 */
    fun auxiliary(eligibleSources: List<FeedbackListSourceFactsPo>): FeedbackListProjectionAuxiliaryFacts {
        val sources = eligibleSources.distinctBy(FeedbackListSourceFactsPo::id)
        val ids = boundedIds(sources.map(FeedbackListSourceFactsPo::id))
        if (ids.isEmpty()) return emptyAuxiliary()
        return auxiliary(sources, dictionary.applicationNames())
    }

    /** 全量验证可复用同一事务内已读取的静态字典，避免每个 keyset 批次重复查询。 */
    fun auxiliary(
        eligibleSources: List<FeedbackListSourceFactsPo>,
        applicationNames: List<String>
    ): FeedbackListProjectionAuxiliaryFacts {
        val sources = eligibleSources.distinctBy(FeedbackListSourceFactsPo::id)
        val ids = boundedIds(sources.map(FeedbackListSourceFactsPo::id))
        if (ids.isEmpty()) return emptyAuxiliary()
        val workflow = workflowFacts.load(ids)
        val imports = importFacts.load(sources, ids)
        val clusters = clusterFacts.load(ids)
        return FeedbackListProjectionAuxiliaryFacts(
            workflow.ai, workflow.workflow, workflow.assignments, workflow.handoffs, workflow.attachments,
            imports.batches, imports.rows, clusters.links, clusters.identityTypes, channelFacts.load(sources),
            clusters.clusters, clusters.identityFingerprints, clusters.decisions, applicationNames
        )
    }

    fun applicationNames(): List<String> = dictionary.applicationNames()

    fun replaceBatch(generationId: Long, requestedSourceIds: List<Long>, projections: List<FeedbackListProjectionPo>,
                     members: List<FeedbackClusterMemberProjectionPo>, identities: List<FeedbackClusterIdentityProjectionPo> = emptyList()) =
        store.replaceBatch(generationId, requestedSourceIds, projections, members, identities)
    fun advanceBuild(generationId: Long, cursorSourceId: Long, projectedDelta: Long, rejectedDelta: Long) =
        generations.advanceBuild(generationId, cursorSourceId, projectedDelta, rejectedDelta)

    private fun emptyAuxiliary() = FeedbackListProjectionAuxiliaryFacts(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
    )

    companion object {
        const val MAX_BATCH_SIZE = 200
        const val ANALYSIS_PROJECTION_VERSION = 1
    }
}
