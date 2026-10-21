package com.feedback.service.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.cluster.Cluster
import com.feedback.model.cluster.ClusterLink
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.cluster.stableClusterNameKey
import com.feedback.model.po.cluster.ClusterCandidateClaimPo
import com.feedback.model.po.cluster.ClusterInsertPo
import com.feedback.model.po.cluster.ClusterLinkInsertPo
import com.feedback.model.po.cluster.ClusterMainDeactivatePo
import com.feedback.model.po.cluster.ClusterStatusCasPo
import com.feedback.repository.cluster.ClusterCommandLedgerRepository
import com.feedback.repository.cluster.ClusterLedgerClaim
import com.feedback.manager.cluster.ClusterMutationAccessGuard
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.query.ClusterReviewCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.util.UUID

data class SplitPartition(val name: String, val sourceIds: List<Long>)

@Service
class ClusterLifecycleService(
    private val repository: ClusterMutationRepository,
    private val profileFacts: ClusterProfileFactRepository,
    private val commandLedger: ClusterCommandLedgerRepository,
    private val accessGuard: ClusterMutationAccessGuard,
    private val statistics: ClusterStatisticsService,
    private val businessAudits: BusinessAuditService,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val profileExtractor: ProblemProfileExtractor,
    private val productScopes: ProductScopeResolver,
    private val json: ObjectMapper
) {
    @Transactional
    fun transition(clusterId: Long, targetStatus: String, command: ClusterReviewCommand, operatorId: Long) {
        validate(command)
        ensureClusterRequest(clusterId > 0) { "CLUSTER_ID_INVALID" }
        val target = targetStatus.uppercase()
        val claim = claim("CLUSTER_STATUS_CHANGE", clusterId, command, listOf(target))
        if (claim.completed) return
        val cluster = repository.lockClusters(listOf(clusterId)).single()
        accessGuard.requireClusters(listOf(clusterId))
        ensureClusterInvariant(cluster.lifecycleVersion == command.expectedVersion, ErrorCode.CLUSTER_REVIEW_CONFLICT) {
            "CLUSTER_LIFECYCLE_VERSION_CONFLICT"
        }
        ensureClusterRequest(target in (TRANSITIONS[cluster.clusterStatus] ?: emptySet())) { "CLUSTER_STATUS_TRANSITION_INVALID" }
        repository.updateStatus(ClusterStatusCasPo(
            cluster.id, cluster.lifecycleVersion, target, operatorId, command.reason.trim()
        ))
        audit(operatorId, "CLUSTER_STATUS_CHANGE", clusterId, command,
            beforeClusterIds = listOf(clusterId), afterClusterIds = listOf(clusterId), affectedCount = 1,
            extra = mapOf("from" to cluster.clusterStatus, "to" to target))
        commandLedger.complete(claim.ledgerId, command.requestId, mapOf(
            "changed" to true, "clusterId" to clusterId, "from" to cluster.clusterStatus, "to" to target,
            "affectedCount" to 1
        ))
    }

    @Transactional
    fun merge(targetId: Long, sourceClusterIds: List<Long>, command: ClusterReviewCommand, operatorId: Long) {
        validate(command)
        ensureClusterRequest(targetId > 0) { "CLUSTER_ID_INVALID" }
        ensureClusterRequest(sourceClusterIds.isNotEmpty() && sourceClusterIds.size <= MAX_AFFECTED_SOURCES) {
            "CLUSTER_MERGE_SOURCES_INVALID"
        }
        ensureClusterRequest(sourceClusterIds.all { it > 0 } && sourceClusterIds.distinct().size == sourceClusterIds.size &&
            targetId !in sourceClusterIds) { "CLUSTER_MERGE_SOURCES_INVALID" }
        val sourceIds = sourceClusterIds.sorted()
        val claim = claim("CLUSTER_MERGE", targetId, command, listOf(sourceIds))
        if (claim.completed) return
        val locked = repository.lockClusters((sourceIds + targetId).sorted()).associateBy(Cluster::id)
        val target = locked.getValue(targetId)
        ensureClusterInvariant(target.lifecycleVersion == command.expectedVersion, ErrorCode.CLUSTER_REVIEW_CONFLICT) {
            "CLUSTER_LIFECYCLE_VERSION_CONFLICT"
        }
        ensureClusterRequest(target.clusterStatus in OPERATIONAL) { "CLUSTER_TARGET_TERMINAL" }
        accessGuard.requireClusters(locked.keys)
        val sourceClusters = sourceIds.map(locked::getValue)
        ensureClusterRequest(sourceClusters.all { it.clusterStatus in OPERATIONAL }) { "CLUSTER_SOURCE_TERMINAL" }
        ensureClusterRequest(sourceClusters.all { compatible(target, it) }) { "CLUSTER_MERGE_PROFILE_CONFLICT" }

        val movedLinks = repository.lockActiveMainsByClusters(sourceIds, MAX_AFFECTED_SOURCES)
        ensureClusterRequest(movedLinks.isNotEmpty()) { "CLUSTER_MERGE_SOURCE_EMPTY" }
        accessGuard.requireSources(movedLinks.map(ClusterLink::feedbackSourceId))
        repository.deactivateMainLinks(movedLinks.map { old ->
            ClusterMainDeactivatePo(old.id, old.reviewVersion, operatorId, command.reason.trim(), null,
                "MERGE_MIGRATION 迁移，旧主关联停用")
        })
        repository.insertLinks(movedLinks.map { old -> old.migrated(
            targetClusterId = targetId, linkType = "MERGE_MIGRATION", operatorId = operatorId,
            reason = command.reason.trim(), decisionReason = "MERGE_MIGRATION 人工迁移"
        ) })
        repository.updateStatuses(sourceClusters.map { source ->
            ClusterStatusCasPo(source.id, source.lifecycleVersion, "MERGED", operatorId,
                command.reason.trim(), mergedIntoClusterId = targetId, clearStatistics = true)
        })
        repository.bumpLifecycle(ClusterStatusCasPo(
            target.id, target.lifecycleVersion, target.clusterStatus, operatorId, command.reason.trim()
        ))
        (sourceIds + targetId).distinct().sorted().chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH)
            .forEach(statistics::recompute)
        audit(operatorId, "CLUSTER_MERGE", targetId, command,
            beforeClusterIds = sourceIds, afterClusterIds = listOf(targetId), affectedCount = movedLinks.size,
            extra = mapOf("sourceClusterIds" to sourceIds, "targetClusterId" to targetId))
        dirty(movedLinks.map(ClusterLink::feedbackSourceId))
        commandLedger.complete(claim.ledgerId, command.requestId, mapOf(
            "merged" to true, "sourceClusterIds" to sourceIds, "targetClusterId" to targetId,
            "affectedCount" to movedLinks.size
        ))
    }

    @Transactional
    fun split(
        sourceClusterId: Long,
        partitions: List<SplitPartition>,
        command: ClusterReviewCommand,
        operatorId: Long
    ): List<Long> {
        validate(command)
        ensureClusterRequest(sourceClusterId > 0) { "CLUSTER_ID_INVALID" }
        validatePartitions(partitions)
        val allSourceIds = partitions.flatMap(SplitPartition::sourceIds)
        val partitionFingerprint = partitions.map { stableClusterNameKey(it.name.trim()) to it.sourceIds.sorted() }
        val claim = claim("CLUSTER_SPLIT", sourceClusterId, command, listOf(partitionFingerprint))
        if (claim.completed) return replayLongList(claim, "clusterIds")

        val nameKeys = partitions.map { stableClusterNameKey(it.name.trim()) }
        val preexisting = repository.byNameKeys(nameKeys)
        val locked = repository.lockClusters((preexisting.map(Cluster::id) + sourceClusterId).distinct().sorted())
            .associateBy(Cluster::id)
        val source = locked[sourceClusterId] ?: throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_NOT_FOUND")
        ensureClusterInvariant(source.lifecycleVersion == command.expectedVersion, ErrorCode.CLUSTER_REVIEW_CONFLICT) {
            "CLUSTER_LIFECYCLE_VERSION_CONFLICT"
        }
        ensureClusterRequest(source.clusterStatus in OPERATIONAL) { "CLUSTER_SOURCE_TERMINAL" }
        accessGuard.requireClusters(listOf(sourceClusterId))
        ensureClusterRequest(repository.byNameKeys(nameKeys, lock = true).isEmpty()) {
            "CLUSTER_SPLIT_PARTITION_NAME_EXISTS_RENAME_REQUIRED"
        }
        accessGuard.requireSources(allSourceIds)
        val profiles = profilesFor(allSourceIds)
        partitions.forEach { partition ->
            val partitionProfiles = partition.sourceIds.map(profiles::getValue)
            ensureClusterRequest(sameHardPartitionBoundary(partitionProfiles)) { "CLUSTER_SPLIT_PARTITION_PROFILE_CONFLICT" }
            partitionProfiles.filter { it.informationQuality == "ACTIONABLE" }.forEach { profile ->
                ensureClusterRequest(clusterNameCoversProfile(partition.name.trim(), profile)) {
                    "CLUSTER_SPLIT_PARTITION_NAME_PROFILE_CONFLICT"
                }
            }
        }
        val childRows = partitions.map { partition ->
            val partitionProfiles = partition.sourceIds.map(profiles::getValue)
            childInsert(source, partition.name.trim(), sourceClusterId, partitionProfiles)
        }
        val children = repository.insertChildren(childRows)
        val childrenByName = children.associateBy(Cluster::clusterNameKey)
        // 子 cluster 必须在任何 cluster_link 行锁之前创建，保持 cluster → link 的全局锁序。
        val activeLinks = repository.lockActiveMainsByClusters(listOf(sourceClusterId), MAX_AFFECTED_SOURCES)
        ensureClusterRequest(activeLinks.map(ClusterLink::feedbackSourceId).toSet() == allSourceIds.toSet()) {
            "CLUSTER_SPLIT_MUST_PARTITION_ALL_RECORDS"
        }
        val targetBySource = partitions.flatMap { partition ->
            val childId = childrenByName.getValue(stableClusterNameKey(partition.name.trim())).id
            partition.sourceIds.map { it to childId }
        }.toMap()
        val activeBySource = activeLinks.associateBy(ClusterLink::feedbackSourceId)
        repository.deactivateMainLinks(activeLinks.map { old ->
            ClusterMainDeactivatePo(old.id, old.reviewVersion, operatorId, command.reason.trim(), null,
                "SPLIT_MIGRATION 迁移，旧主关联停用")
        })
        repository.insertLinks(allSourceIds.sorted().map { sourceId ->
            val old = activeBySource.getValue(sourceId)
            old.migrated(
                targetClusterId = targetBySource.getValue(sourceId), linkType = "SPLIT_MIGRATION",
                operatorId = operatorId, reason = command.reason.trim(),
                decisionReason = "SPLIT_MIGRATION 人工迁移",
                problemFingerprint = profiles.getValue(sourceId).problemFingerprint
            )
        })
        repository.updateStatus(ClusterStatusCasPo(
            source.id, source.lifecycleVersion, "SPLIT_ARCHIVED", operatorId,
            command.reason.trim(), clearStatistics = true
        ))
        val childIds = partitions.map { childrenByName.getValue(stableClusterNameKey(it.name.trim())).id }
        (childIds + sourceClusterId).distinct().sorted().chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH)
            .forEach(statistics::recompute)
        audit(operatorId, "CLUSTER_SPLIT", sourceClusterId, command,
            beforeClusterIds = listOf(sourceClusterId), afterClusterIds = childIds,
            affectedCount = allSourceIds.size,
            extra = mapOf("sourceClusterId" to sourceClusterId, "childClusterIds" to childIds))
        dirty(allSourceIds)
        commandLedger.complete(claim.ledgerId, command.requestId, mapOf(
            "clusterIds" to childIds, "sourceClusterId" to sourceClusterId,
            "affectedCount" to allSourceIds.size
        ))
        return childIds
    }

    @Transactional
    fun reopen(clusterId: Long, recurrenceSourceId: Long, command: ClusterReviewCommand, operatorId: Long) {
        validate(command)
        ensureClusterRequest(clusterId > 0 && recurrenceSourceId > 0) { "CLUSTER_ID_INVALID" }
        val claim = claim("CLUSTER_REOPEN", clusterId, command, listOf(recurrenceSourceId))
        if (claim.completed) return
        val cluster = repository.lockClusters(listOf(clusterId)).single()
        ensureClusterInvariant(cluster.lifecycleVersion == command.expectedVersion, ErrorCode.CLUSTER_REVIEW_CONFLICT) {
            "CLUSTER_LIFECYCLE_VERSION_CONFLICT"
        }
        ensureClusterRequest(cluster.clusterStatus == "RESOLVED") { "CLUSTER_REOPEN_ONLY_RESOLVED" }
        accessGuard.requireClusters(listOf(clusterId))
        accessGuard.requireSources(listOf(recurrenceSourceId))
        val lockedLinks = repository.linksBySources(listOf(recurrenceSourceId), lock = true)
        ensureClusterRequest(lockedLinks.none { it.isActive && it.relationRole == "MAIN" }) { "CLUSTER_REOPEN_MAIN_EXISTS" }
        val candidates = lockedLinks.filter {
            it.isActive && it.relationRole == "CANDIDATE" && it.reviewStatus == "PENDING"
        }
        val selected = candidates.firstOrNull { it.clusterId == clusterId }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REOPEN_CANDIDATE_REQUIRED")
        ensureClusterRequest(selected.matchEvidence?.contains("\"historical_recurrence\":true") == true) {
            "CLUSTER_REOPEN_STRONG_EVIDENCE_REQUIRED"
        }
        ensureClusterRequest(candidates.map(ClusterLink::reviewVersion).distinct().size == 1) {
            "CLUSTER_REVIEW_VERSION_CONFLICT"
        }
        val candidateVersion = candidates.first().reviewVersion
        repository.claimCandidates(listOf(ClusterCandidateClaimPo(
            recurrenceSourceId, candidateVersion, clusterId, "CONFIRMED", operatorId,
            command.reason.trim(), command.requestId, true
        )), candidates.size)
        val profile = profilesFor(listOf(recurrenceSourceId)).getValue(recurrenceSourceId)
        repository.insertLinks(listOf(ClusterLinkInsertPo(
            clusterId = clusterId, feedbackSourceId = recurrenceSourceId,
            relationRole = "MAIN", linkType = "REOPENED",
            matchSimilarity = selected.matchSimilarity, matchEvidence = selected.matchEvidence,
            decisionVersion = selected.decisionVersion,
            decisionConfigVersion = selected.decisionConfigVersion,
            featureSnapshot = json.writeValueAsString(profile), problemFingerprint = profile.problemFingerprint,
            reviewStatus = "CONFIRMED", reviewVersion = candidateVersion + 1,
            reviewedBy = operatorId, reviewReason = command.reason.trim(),
            decisionReason = "已解决问题强证据复发，人工重开"
        )))
        repository.updateStatus(ClusterStatusCasPo(
            cluster.id, cluster.lifecycleVersion, "REOPENED", operatorId, command.reason.trim()
        ))
        statistics.recomputeOne(clusterId)
        audit(operatorId, "CLUSTER_REOPEN", clusterId, command,
            beforeClusterIds = listOf(clusterId), afterClusterIds = listOf(clusterId), affectedCount = 1,
            extra = mapOf("recurrenceSourceId" to recurrenceSourceId, "evidence" to "historical_recurrence"))
        dirty(listOf(recurrenceSourceId))
        commandLedger.complete(claim.ledgerId, command.requestId, mapOf(
            "reopened" to true, "clusterId" to clusterId, "recurrenceSourceId" to recurrenceSourceId,
            "affectedCount" to 1
        ))
    }

    private fun profilesFor(sourceIds: List<Long>): Map<Long, ProblemProfile> {
        val facts = profileFacts.load(sourceIds)
        if (facts.sources.size != sourceIds.size || facts.contents.size != sourceIds.size) {
            throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        }
        val productScopesBySource = productScopes.resolveAll(facts.sources.values, facts.identifiers)
        return sourceIds.associateWith { sourceId ->
            val source = facts.sources.getValue(sourceId)
            profileExtractor.extract(
                source,
                facts.contents.getValue(sourceId),
                facts.ai[sourceId] ?: FeedbackAi(sourceId = sourceId, reviewStatus = "NOT_REQUIRED"),
                productScopesBySource.getValue(sourceId),
                facts.contexts[sourceId]?.asMap().orEmpty()
            )
        }
    }

    private fun childInsert(
        source: Cluster,
        name: String,
        parent: Long,
        profiles: List<ProblemProfile>
    ): ClusterInsertPo {
        val products = profiles.map(ProblemProfile::productScope).filter(String::isNotBlank).distinct()
        val modules = profiles.mapNotNull { it.functionModule?.takeIf(String::isNotBlank) }.distinct()
        val categories = profiles.mapNotNull { it.issueCategory?.takeIf(String::isNotBlank) }.distinct()
        return ClusterInsertPo(
            publicId = uuidBytes(), clusterName = name, clusterNameKey = stableClusterNameKey(name),
            productScope = products.singleOrNull() ?: source.productScope,
            functionModule = modules.singleOrNull(), issueCategory = categories.singleOrNull(),
            canonicalSymptom = name,
            canonicalObject = profiles.firstNotNullOfOrNull(ProblemProfile::targetObject),
            canonicalTrigger = profiles.firstNotNullOfOrNull(ProblemProfile::trigger),
            errorSignature = profiles.firstNotNullOfOrNull(ProblemProfile::errorSignature),
            splitFromClusterId = parent
        )
    }

    private fun ClusterLink.migrated(
        targetClusterId: Long,
        linkType: String,
        operatorId: Long,
        reason: String,
        decisionReason: String,
        problemFingerprint: String? = this.problemFingerprint
    ) = ClusterLinkInsertPo(
        clusterId = targetClusterId, feedbackSourceId = feedbackSourceId, relationRole = "MAIN",
        linkType = linkType, matchSimilarity = matchSimilarity, matchEvidence = matchEvidence,
        decisionVersion = decisionVersion, decisionConfigVersion = decisionConfigVersion,
        featureSnapshot = featureSnapshot, problemFingerprint = problemFingerprint,
        reviewStatus = "CONFIRMED", reviewVersion = reviewVersion + 1,
        reviewedBy = operatorId, reviewReason = reason, decisionReason = decisionReason,
        reclassificationRunId = reclassificationRunId
    )

    private fun validatePartitions(partitions: List<SplitPartition>) {
        ensureClusterRequest(partitions.size in 2..MAX_AFFECTED_SOURCES) { "CLUSTER_SPLIT_PARTITIONS_REQUIRED" }
        ensureClusterRequest(partitions.all { partition ->
            partition.name.trim().length in 1..200 && partition.sourceIds.isNotEmpty() &&
                partition.sourceIds.all { it > 0 }
        }) { "CLUSTER_SPLIT_PARTITION_INVALID" }
        ensureClusterRequest(partitions.map { stableClusterNameKey(it.name.trim()) }.distinct().size == partitions.size) {
            "CLUSTER_SPLIT_PARTITION_NAME_DUPLICATED"
        }
        val allIds = partitions.flatMap(SplitPartition::sourceIds)
        ensureClusterRequest(allIds.size <= MAX_AFFECTED_SOURCES) { "CLUSTER_AFFECTED_SOURCE_LIMIT_EXCEEDED" }
        ensureClusterRequest(allIds.distinct().size == allIds.size) { "CLUSTER_SPLIT_DUPLICATE_RECORD" }
    }

    private fun validate(command: ClusterReviewCommand) {
        ensureClusterRequest(command.expectedVersion >= 0) { "CLUSTER_LIFECYCLE_VERSION_INVALID" }
        runCatching { UUID.fromString(command.requestId) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_REQUEST_ID_INVALID", cause = it) }
        ensureClusterRequest(command.reason.trim().length in 3..500) { "CLUSTER_REASON_INVALID" }
    }

    private fun claim(
        operation: String, targetId: Long, command: ClusterReviewCommand, extra: List<Any?>
    ): ClusterLedgerClaim {
        val fingerprint = commandLedger.fingerprint(
            operation, targetId, command.expectedVersion, command.reason.trim(), extra
        )
        val claim = commandLedger.claim(command.requestId, operation, fingerprint, targetId)
        if (claim.operation != operation || claim.commandFingerprint != fingerprint) {
            throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REQUEST_ID_COMMAND_CONFLICT")
        }
        return claim
    }

    private fun audit(
        operatorId: Long,
        operation: String,
        targetId: Long,
        command: ClusterReviewCommand,
        beforeClusterIds: List<Long>,
        afterClusterIds: List<Long>,
        affectedCount: Int,
        extra: Map<String, Any?>
    ) {
        businessAudits.append(BusinessAuditEvent(
            operatorId, operation, "BUSINESS", "SUCCESS", "CLUSTER", targetId.toString(),
            command.requestId, null, extra + mapOf(
                "actor" to operatorId, "requestId" to command.requestId,
                "expectedVersion" to command.expectedVersion, "reason" to command.reason.trim(),
                "beforeClusterIds" to beforeClusterIds, "afterClusterIds" to afterClusterIds,
                "affectedCount" to affectedCount
            )
        ))
    }

    private fun dirty(sourceIds: Collection<Long>) {
        dirtyMarker.mark(FeedbackListProjectionDirtyCommand(
            sourceIds.distinct().sorted(), FeedbackListProjectionDirtyReason.CLUSTER
        ))
    }

    private fun replayLongList(claim: ClusterLedgerClaim, field: String): List<Long> {
        val node = claim.result?.get(field)
            ?: throw ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REPLAY_RESULT_MISSING")
        ensureClusterInvariant(node.isArray) { "CLUSTER_REPLAY_RESULT_INVALID" }
        return node.map { it.asLong() }
    }

    private fun compatible(a: Cluster, b: Cluster): Boolean {
        val targetA = ClusterProblemOntology.targetClass(a.canonicalObject)
        val targetB = ClusterProblemOntology.targetClass(b.canonicalObject)
        if (targetA != null && targetB != null && targetA != targetB) return false
        val actionA = ClusterProblemOntology.operationClass(a.canonicalTrigger)
        val actionB = ClusterProblemOntology.operationClass(b.canonicalTrigger)
        if (actionA != null && actionB != null && actionA != actionB) return false
        val modesA = ClusterProblemOntology.failureModes(a.canonicalSymptom.orEmpty())
        val modesB = ClusterProblemOntology.failureModes(b.canonicalSymptom.orEmpty())
        val causalSubmission = modesA + modesB == setOf("NO_RESPONSE", "SUBMIT")
        if (modesA.isNotEmpty() && modesB.isNotEmpty() && modesA != modesB && !causalSubmission) return false
        val signatureA = a.errorSignature?.takeIf(String::isNotBlank)
        val signatureB = b.errorSignature?.takeIf(String::isNotBlank)
        return signatureA == null || signatureB == null || signatureA == signatureB
    }

    private fun uuidBytes(): ByteArray = UUID.randomUUID().let {
        ByteBuffer.allocate(16).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits).array()
    }

    companion object {
        const val MAX_AFFECTED_SOURCES = 200
        private val OPERATIONAL = setOf("ACTIVE", "MITIGATING", "RESOLVED", "REOPENED")
        private val TRANSITIONS = mapOf(
            "ACTIVE" to setOf("MITIGATING"),
            "MITIGATING" to setOf("RESOLVED"),
            "REOPENED" to setOf("MITIGATING", "RESOLVED")
        )
    }
}

internal fun sameHardPartitionBoundary(profiles: List<ProblemProfile>): Boolean {
    if (profiles.mapNotNull(ProblemProfile::capability).distinct().size > 1) return false
    if (profiles.mapNotNull(ProblemProfile::scenarioOrAction).distinct().size > 1) return false
    if (profiles.mapNotNull { it.errorSignature?.takeIf(String::isNotBlank) }.distinct().size > 1) return false
    val modeSets = profiles.map(ProblemProfile::failureModes).filter(Set<String>::isNotEmpty).distinct()
    return modeSets.size <= 1 || modeSets.flatMap { it }.toSet() == setOf("NO_RESPONSE", "SUBMIT")
}
