package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.service.sync.EmbeddingProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest

data class ClusterShadowGenerationCommand(val generationKey: String, val actorId: Long)

data class ClusterShadowGenerationResult(
    val generationId: Long,
    val generationKey: String,
    val generationStatus: String,
    val algorithmVersion: String,
    val modelVersion: String,
    val processedCount: Long,
    val actionableCount: Long,
    val terminalCount: Long,
    val clusterCount: Long,
    val provisionalCount: Long,
    val assignmentDigest: String?,
    val verificationDigest: String?
)

data class ClusterShadowGenerationPreflightResult(
    val algorithmVersion: String,
    val modelVersion: String,
    val expectedSourceCount: Long
)

/**
 * 构建 shadow-11 的隔离候选 generation。这里只有 candidate 表写入能力，不依赖 Cluster/Link Mapper，
 * 因而 BUILD/VERIFY 都不能改变现有 MAIN 或任何 ACTIVE generation。
 */
@Service
class ClusterShadowGenerationService(
    private val repository: ClusterShadowGenerationRepository,
    private val input: HistoricalClusterShadowInputPort,
    private val embeddings: EmbeddingProvider
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val extractor = AtomicProblemExtractorV4()

    fun preflight(command: ClusterShadowGenerationCommand): ClusterShadowGenerationPreflightResult {
        generationKey(command.generationKey)
        ensureClusterRequest(command.actorId > 0) { "CLUSTER_SHADOW_GENERATION_ACTOR_INVALID" }
        ensureSemanticRuntime()
        val snapshot = readSnapshot()
        ensureClusterRequest(snapshot.records.isNotEmpty()) { "CLUSTER_SHADOW_GENERATION_INPUT_EMPTY" }
        return ClusterShadowGenerationPreflightResult(
            AtomicProblemExtractorV4.VERSION,
            embeddings.modelVersion,
            snapshot.records.size.toLong()
        )
    }

    /** 只关闭被显式取代的治理前槽位；候选证据保持完整。 */
    fun abandonStale(command: ClusterShadowGenerationCommand): ClusterShadowGenerationResult {
        val key = generationKey(command.generationKey)
        ensureClusterRequest(command.actorId > 0) { "CLUSTER_SHADOW_GENERATION_ACTOR_INVALID" }
        ensureClusterRequest(key == STALE_PRE_GOVERNANCE_GENERATION) {
            "CLUSTER_SHADOW_GENERATION_ABANDON_SCOPE_INVALID"
        }
        val generation = repository.findByKey(key)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_NOT_FOUND")
        ensureClusterRequest(generation.generationStatus == "BUILDING") {
            "CLUSTER_SHADOW_GENERATION_NOT_BUILDING"
        }
        repository.markFailed(
            key,
            "CLUSTER_SHADOW_GENERATION_SUPERSEDED",
            "Pre-governance shadow generation superseded; candidate evidence retained"
        )
        val closed = repository.findByKey(key)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_NOT_FOUND")
        ensureClusterInvariant(closed.generationStatus == "FAILED") {
            "CLUSTER_SHADOW_GENERATION_ABANDON_CAS_FAILED"
        }
        return result(closed)
    }

    fun build(command: ClusterShadowGenerationCommand): ClusterShadowGenerationResult {
        val key = generationKey(command.generationKey)
        ensureClusterRequest(command.actorId > 0) { "CLUSTER_SHADOW_GENERATION_ACTOR_INVALID" }
        ensureSemanticRuntime()
        val snapshot = readSnapshot()
        ensureClusterRequest(snapshot.records.isNotEmpty()) { "CLUSTER_SHADOW_GENERATION_INPUT_EMPTY" }
        val inputs = snapshot.records.map { it.classificationInput }
        val profiles = inputs.associate { it.sourceId to extractor.extract(it) }
        val semanticVectors = precomputeSemanticVectors(inputs, profiles)
        val row = repository.createBuilding(ClusterShadowGenerationPo(
            generationKey = key,
            algorithmVersion = AtomicProblemExtractorV4.VERSION,
            modelVersion = embeddings.modelVersion,
            buildFenceSourceId = snapshot.fence,
            expectedSourceCount = snapshot.records.size.toLong(),
            createdBy = command.actorId
        ))
        return try {
            val semanticFallbacks = mutableListOf<SemanticRecallFallbackReason>()
            val classifier = classifier(semanticVectors, semanticFallbacks::add)
            val forward = classifier.classify(inputs)
            val reverse = classifier.classify(inputs.reversed())
            ensureClusterInvariant(semanticFallbacks.isEmpty()) {
                "CLUSTER_SHADOW_GENERATION_SEMANTIC_DEGRADED"
            }
            val members = candidateMembers(row.id, forward.assignments, profiles)
            val reverseMembers = candidateMembers(row.id, reverse.assignments, profiles)
            val forwardDigest = memberDigest(members)
            val reverseDigest = memberDigest(reverseMembers)
            ensureClusterInvariant(forwardDigest == reverseDigest) {
                "CLUSTER_SHADOW_GENERATION_ORDER_DRIFT"
            }
            val clusters = candidateClusters(row.id, forward.assignments, members)
            validateNames(forward.assignments)
            row.processedCount = members.size.toLong()
            row.actionableCount = forward.assignments.count { it.status == ProblemClassificationStatus.ACTIONABLE }.toLong()
            row.terminalCount = forward.assignments.count { it.status != ProblemClassificationStatus.ACTIONABLE }.toLong()
            row.clusterCount = clusters.size.toLong()
            row.provisionalCount = members.count { it.decisionCode.startsWith("PROVISIONAL_") }.toLong()
            row.inputDigest = snapshot.digest
            row.assignmentDigest = forwardDigest
            row.reverseAssignmentDigest = reverseDigest
            repository.persistBuild(row, clusters, members)
            log.info(
                "Cluster shadow generation built: generationId={}, status=BUILDING, algorithm={}, processed={}, clusters={}",
                row.id, row.algorithmVersion, row.processedCount, row.clusterCount
            )
            result(row)
        } catch (error: Throwable) {
            runCatching {
                repository.markFailed(key, safeFailureCode(error, "CLUSTER_SHADOW_GENERATION_BUILD_FAILED"),
                    "Shadow generation build failed; inspect protected logs")
            }
            throw error
        }
    }

    fun verify(command: ClusterShadowGenerationCommand): ClusterShadowGenerationResult {
        val key = generationKey(command.generationKey)
        ensureClusterRequest(command.actorId > 0) { "CLUSTER_SHADOW_GENERATION_ACTOR_INVALID" }
        ensureSemanticRuntime()
        val generation = repository.findByKey(key)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_NOT_FOUND")
        ensureClusterRequest(generation.generationStatus == "BUILDING") {
            "CLUSTER_SHADOW_GENERATION_NOT_BUILDING"
        }
        return try {
            val current = readSnapshot()
            ensureClusterInvariant(current.fence == generation.buildFenceSourceId &&
                current.records.size.toLong() == generation.expectedSourceCount &&
                current.digest == generation.inputDigest) {
                "CLUSTER_SHADOW_GENERATION_INPUT_DRIFT"
            }
            val semanticFallbacks = mutableListOf<SemanticRecallFallbackReason>()
            val currentInputs = current.records.map { it.classificationInput }
            val replayProfiles = currentInputs.associate { it.sourceId to extractor.extract(it) }
            val semanticVectors = precomputeSemanticVectors(currentInputs, replayProfiles)
            val replay = classifier(semanticVectors, semanticFallbacks::add).classify(currentInputs)
            ensureClusterInvariant(semanticFallbacks.isEmpty()) {
                "CLUSTER_SHADOW_GENERATION_SEMANTIC_DEGRADED"
            }
            validateNames(replay.assignments)
            val replayMembers = candidateMembers(generation.id, replay.assignments, replayProfiles)
            ensureClusterInvariant(memberDigest(replayMembers) == generation.assignmentDigest) {
                "CLUSTER_SHADOW_GENERATION_REPLAY_DRIFT"
            }
            val members = repository.members(generation.id)
            val clusters = repository.clusters(generation.id)
            val replayClusters = candidateClusters(generation.id, replay.assignments, replayMembers)
            validatePersisted(generation, current, members, clusters)
            ensureClusterInvariant(replayMembers == members && replayClusters == clusters) {
                "CLUSTER_SHADOW_GENERATION_PERSISTENCE_DRIFT"
            }
            val verificationDigest = verificationDigest(generation, members, clusters)
            val verified = repository.markVerified(key, command.actorId, verificationDigest)
            log.info(
                "Cluster shadow generation verified: generationId={}, status=VERIFIED, processed={}, clusters={}",
                verified.id, verified.processedCount, verified.clusterCount
            )
            result(verified)
        } catch (error: Throwable) {
            runCatching {
                repository.markFailed(key, safeFailureCode(error, "CLUSTER_SHADOW_GENERATION_VERIFY_FAILED"),
                    "Shadow generation verification failed; inspect protected logs")
            }
            throw error
        }
    }

    private fun validatePersisted(
        generation: ClusterShadowGenerationPo,
        snapshot: InputSnapshot,
        members: List<ClusterShadowCandidateMemberPo>,
        clusters: List<ClusterShadowCandidateClusterPo>
    ) {
        ensureClusterInvariant(generation.algorithmVersion == AtomicProblemExtractorV4.VERSION) {
            "CLUSTER_SHADOW_GENERATION_ALGORITHM_DRIFT"
        }
        ensureClusterInvariant(generation.modelVersion == embeddings.modelVersion) {
            "CLUSTER_SHADOW_GENERATION_MODEL_DRIFT"
        }
        ensureClusterInvariant(members.size.toLong() == generation.processedCount &&
            generation.processedCount == generation.expectedSourceCount) {
            "CLUSTER_SHADOW_GENERATION_COVERAGE_MISMATCH"
        }
        ensureClusterInvariant(members.map { it.feedbackSourceId } == members.map { it.feedbackSourceId }.sorted() &&
            members.map { it.feedbackSourceId }.distinct().size == members.size &&
            members.map { it.feedbackSourceId }.toSet() == snapshot.records.map { it.classificationInput.sourceId }.toSet()) {
            "CLUSTER_SHADOW_GENERATION_MEMBER_SET_MISMATCH"
        }
        val actionable = members.count { it.problemStatus == ProblemClassificationStatus.ACTIONABLE.name }
        ensureClusterInvariant(actionable.toLong() == generation.actionableCount &&
            members.size.toLong() - actionable == generation.terminalCount) {
            "CLUSTER_SHADOW_GENERATION_STATUS_COUNT_MISMATCH"
        }
        ensureClusterInvariant(members.all {
            if (it.manualLocked) it.clusterKeyHash != null
            else (it.problemStatus == ProblemClassificationStatus.ACTIONABLE.name) == (it.clusterKeyHash != null)
        }) { "CLUSTER_SHADOW_GENERATION_TERMINAL_CLUSTER_INVALID" }
        val grouped = members.filter { it.clusterKeyHash != null }.groupBy { it.clusterKeyHash!! }
        ensureClusterInvariant(grouped.size.toLong() == generation.clusterCount && clusters.size == grouped.size) {
            "CLUSTER_SHADOW_GENERATION_CLUSTER_COUNT_MISMATCH"
        }
        val clusterByKey = clusters.associateBy { it.clusterKeyHash }
        ensureClusterInvariant(clusterByKey.size == clusters.size && grouped.all { (key, rows) ->
            val summary = clusterByKey[key] ?: return@all false
            summary.memberCount == rows.size.toLong() &&
                summary.provisionalMemberCount == rows.count { it.decisionCode.startsWith("PROVISIONAL_") }.toLong() &&
                summary.manualMemberCount == rows.count { it.manualLocked }.toLong()
        }) { "CLUSTER_SHADOW_GENERATION_CLUSTER_SUMMARY_MISMATCH" }
        ensureClusterInvariant(grouped.values.all(::hardBoundariesCompatible)) {
            "CLUSTER_SHADOW_GENERATION_HARD_BOUNDARY_CONFLICT"
        }
        ensureClusterInvariant(generation.assignmentDigest == generation.reverseAssignmentDigest &&
            generation.assignmentDigest == memberDigest(members)) {
            "CLUSTER_SHADOW_GENERATION_DIGEST_MISMATCH"
        }
        ensureClusterInvariant(members.count { it.decisionCode.startsWith("PROVISIONAL_") }.toLong() ==
            generation.provisionalCount) { "CLUSTER_SHADOW_GENERATION_PROVISIONAL_COUNT_MISMATCH" }
    }

    /** 人工锁定结论是不可变输入；自动成员才必须满足五字段零冲突。 */
    private fun hardBoundariesCompatible(rows: List<ClusterShadowCandidateMemberPo>): Boolean {
        val automatic = rows.filterNot { it.manualLocked }
        fun distinct(selector: (ClusterShadowCandidateMemberPo) -> String?) = automatic.map(selector).toSet().size <= 1
        return distinct { it.applicationHash } && distinct { it.failureModeHash } && distinct { it.targetHash } &&
            distinct { it.actionHash } && distinct { it.errorSignatureHash }
    }

    private fun candidateMembers(
        generationId: Long,
        assignments: List<ShadowClusterAssignment>,
        profiles: Map<Long, AtomicProblemProfile>
    ) = assignments.sortedBy { it.sourceId }.map { assignment ->
        val profile = profiles[assignment.sourceId] ?: clusterInvariantViolation("CLUSTER_SHADOW_PROFILE_MISSING")
        ClusterShadowCandidateMemberPo(
            generationId, assignment.sourceId, assignment.status.name,
            assignment.clusterKey?.let { hash("cluster-key", it) }, assignment.decision, assignment.score,
            assignment.manualLocked, hashNullable("application", profile.applicationKey),
            hashNullable("failure-mode", profile.failureMode), hashNullable("target", profile.target),
            hashNullable("action", profile.action), hashNullable("error-signature", profile.errorSignature)
        )
    }

    private fun candidateClusters(
        generationId: Long,
        assignments: List<ShadowClusterAssignment>,
        members: List<ClusterShadowCandidateMemberPo>
    ): List<ClusterShadowCandidateClusterPo> {
        val assignmentBySource = assignments.associateBy { it.sourceId }
        return members.filter { it.clusterKeyHash != null }.groupBy { it.clusterKeyHash!! }.map { (key, rows) ->
            val names = rows.mapNotNull { assignmentBySource[it.feedbackSourceId]?.clusterName }.toSet()
            ensureClusterInvariant(names.size == 1) { "CLUSTER_SHADOW_GENERATION_CLUSTER_NAME_DRIFT" }
            ClusterShadowCandidateClusterPo(
                generationId, key, hash("cluster-name", names.single()), rows.size.toLong(),
                rows.count { it.decisionCode.startsWith("PROVISIONAL_") }.toLong(),
                rows.count { it.manualLocked }.toLong()
            )
        }.sortedBy { it.clusterKeyHash }
    }

    private fun validateNames(assignments: List<ShadowClusterAssignment>) {
        ensureClusterInvariant(assignments.filter { it.clusterKey != null }.all {
            val name = it.clusterName.orEmpty()
            name.isNotBlank() && name.length <= 200 && (it.manualLocked || '·' in name)
        }) { "CLUSTER_SHADOW_GENERATION_NAME_QUALITY_FAILED" }
    }

    private fun classifier(
        semanticVectors: Map<String, List<Float>>,
        observer: (SemanticRecallFallbackReason) -> Unit
    ) = ClusterShadowClassifierV3(extractor = extractor, recall = SemanticCandidateRecallV3(
        embeddings = SemanticEmbeddingPort { text ->
            semanticVectors[text] ?: clusterInvariantViolation("CLUSTER_SHADOW_GENERATION_VECTOR_MISSING")
        },
        fallbackObserver = observer
    ))

    /** 先固化真实的 BGE-M3 向量再建 BUILDING 行，避免冷缓存造成超时漂移。 */
    private fun precomputeSemanticVectors(
        inputs: List<ClusterClassificationInput>,
        profiles: Map<Long, AtomicProblemProfile>
    ): Map<String, List<Float>> {
        val texts = inputs.asSequence()
            .filter { it.manualConclusion == null }
            .map { input -> profiles[input.sourceId] ?: clusterInvariantViolation("CLUSTER_SHADOW_PROFILE_MISSING") }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .map(ClusterSemanticTextV3::of)
            .distinct()
            .sorted()
            .toList()
        return texts.associateWith { text ->
            val vector = try {
                embeddings.embed(text)
            } catch (error: Exception) {
                throw ServiceException(
                    ErrorCode.INTERNAL_ERROR,
                    "CLUSTER_SHADOW_GENERATION_BGE_M3_PRECOMPUTE_FAILED",
                    cause = error
                )
            }
            ensureClusterInvariant(vector.size == embeddings.dimension && vector.all(Float::isFinite) &&
                vector.any { it != 0f }) {
                "CLUSTER_SHADOW_GENERATION_BGE_M3_VECTOR_INVALID"
            }
            vector
        }
    }

    private fun ensureSemanticModel() {
        ensureClusterRequest(embeddings.dimension == 1024 &&
            !embeddings.modelVersion.startsWith("hashing", ignoreCase = true)) {
            "CLUSTER_SHADOW_GENERATION_BGE_M3_REQUIRED"
        }
    }

    private fun ensureSemanticRuntime() {
        ensureSemanticModel()
        val probe = runCatching { embeddings.embed("语义模型运行门禁") }.getOrElse {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_BGE_M3_RUNTIME_FAILED", cause = it)
        }
        ensureClusterRequest(probe.size == embeddings.dimension && probe.all(Float::isFinite) &&
            probe.any { it != 0f }) {
            "CLUSTER_SHADOW_GENERATION_BGE_M3_PROBE_INVALID"
        }
    }

    private fun readSnapshot(): InputSnapshot {
        val rows = mutableListOf<HistoricalClusterShadowInput>()
        var cursor = 0L
        while (true) {
            val batch = input.read(cursor, HistoricalClusterShadowInputReader.MAX_BATCH_SIZE)
            ensureClusterInvariant(batch.nextSourceId >= cursor) { "CLUSTER_SHADOW_GENERATION_CURSOR_REGRESSION" }
            rows += batch.records
            if (!batch.mayHaveMore || batch.nextSourceId == cursor) {
                cursor = batch.nextSourceId
                break
            }
            cursor = batch.nextSourceId
        }
        ensureClusterInvariant(rows.map { it.classificationInput.sourceId }.distinct().size == rows.size) {
            "CLUSTER_SHADOW_GENERATION_SOURCE_DUPLICATED"
        }
        return InputSnapshot(rows.sortedBy { it.classificationInput.sourceId }, cursor,
            hash("input-snapshot", rows.sortedBy { it.classificationInput.sourceId }.joinToString("\n") {
                inputRowDigest(it.classificationInput)
            }))
    }

    private fun inputRowDigest(value: ClusterClassificationInput) = hash("input-row", listOf(
        value.sourceId.toString(), value.applicationKey, value.applicationName, value.title, value.description,
        value.functionModule, value.issueCategory, value.applicationAmbiguous.toString(),
        value.contentDisposition.name, value.sourceChannel, value.applicationEvidence.name,
        value.manualConclusion?.clusterKey, value.manualConclusion?.clusterName
    ).joinToString("|") { part -> part?.let { "${it.length}:$it" } ?: "-" })

    private fun memberDigest(rows: List<ClusterShadowCandidateMemberPo>) = hash("assignment-set",
        rows.sortedBy { it.feedbackSourceId }.joinToString("\n") { row -> listOf(
            row.feedbackSourceId, row.problemStatus, row.clusterKeyHash, row.decisionCode,
            row.score?.toPlainString(), row.manualLocked, row.applicationHash, row.failureModeHash,
            row.targetHash, row.actionHash, row.errorSignatureHash
        ).joinToString("|") })

    private fun verificationDigest(
        generation: ClusterShadowGenerationPo,
        members: List<ClusterShadowCandidateMemberPo>,
        clusters: List<ClusterShadowCandidateClusterPo>
    ) = hash("verification", listOf(
        generation.algorithmVersion, generation.modelVersion, generation.buildFenceSourceId,
        generation.expectedSourceCount, generation.inputDigest, generation.assignmentDigest,
        memberDigest(members), clusters.joinToString("\n") {
            listOf(it.clusterKeyHash, it.clusterNameHash, it.memberCount,
                it.provisionalMemberCount, it.manualMemberCount).joinToString("|")
        }
    ).joinToString("\n"))

    private fun hashNullable(domain: String, value: String?): String? = value?.takeIf(String::isNotBlank)?.let { hash(domain, it) }
    private fun hash(domain: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("cluster-shadow-generation-v1\u0000$domain\u0000$value".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun generationKey(value: String): String = value.trim().also {
        ensureClusterRequest(it.matches(Regex("[a-z0-9][a-z0-9._-]{2,99}"))) {
            "CLUSTER_SHADOW_GENERATION_KEY_INVALID"
        }
    }

    private fun result(row: ClusterShadowGenerationPo) = ClusterShadowGenerationResult(
        row.id, row.generationKey, row.generationStatus, row.algorithmVersion, row.modelVersion,
        row.processedCount, row.actionableCount, row.terminalCount, row.clusterCount,
        row.provisionalCount, row.assignmentDigest, row.verificationDigest
    )

    private fun safeFailureCode(error: Throwable, fallback: String): String =
        (error as? com.feedback.common.exception.AbstractException)?.reasonCode ?: fallback

    private data class InputSnapshot(
        val records: List<HistoricalClusterShadowInput>,
        val fence: Long,
        val digest: String
    )

    companion object {
        const val STALE_PRE_GOVERNANCE_GENERATION = "shadow-11-full-20260908-2310"
    }
}
