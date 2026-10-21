package com.feedback.service.cluster

import com.feedback.service.ai.PromptAssetBootstrap
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.normalization.FaultOccurrenceTimeBackfillRunner
import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import com.hankcs.hanlp.HanLP
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale

/**
 * shadow-9 开发完成后的增量历史独立验证。增量记录只能作为 query，冻结前历史只能作为候选；
 * current cluster 仅参与风险排序，不作标签。发现错误时先报告，禁止回写本集合或在同轮调规则。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_INDEPENDENT_INCREMENTAL_VALIDATION_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3IndependentIncrementalValidationTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val masking: MaskingPipeline
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `开发后新增历史只用于独立冻结验证`() {
        val referenceWindow = readWindow(0, MAX_SCAN_RECORDS)
        val incrementalWindow = readWindow(FREEZE_CURSOR, MAX_INCREMENTAL_RECORDS)
        val referenceRecords = referenceWindow.records.filter { it.classificationInput.sourceId <= FREEZE_CURSOR }
        val incrementalRecords = incrementalWindow.records.filter { it.classificationInput.sourceId > FREEZE_CURSOR }
        assertTrue(referenceRecords.isNotEmpty(), "CLUSTER_INCREMENTAL_REFERENCE_EMPTY")
        assertTrue(referenceRecords.all { it.classificationInput.sourceId <= FREEZE_CURSOR },
            "CLUSTER_INCREMENTAL_REFERENCE_LEAKAGE")
        assertTrue(incrementalRecords.all { it.classificationInput.sourceId > FREEZE_CURSOR },
            "CLUSTER_INCREMENTAL_SOURCE_LEAKAGE")

        val extractor = AtomicProblemExtractorV3()
        val reference = actionable(referenceRecords, extractor)
        val incremental = actionable(incrementalRecords, extractor)
        if (!discoveryMode()) {
            assertTrue(incrementalRecords.isNotEmpty(), "CLUSTER_INCREMENTAL_NO_ELIGIBLE_RECORDS_AFTER_FREEZE")
            assertTrue(incremental.isNotEmpty(), "CLUSTER_INCREMENTAL_NO_ACTIONABLE_RECORDS_AFTER_FREEZE")
        }
        val compatible = compatibleCandidates(incremental, reference)
        val signatureConflicts = signatureConflictCandidates(incremental, reference)
        val allCandidates = (compatible + signatureConflicts).associateBy(Candidate::hash).values.sortedBy(Candidate::hash)
        val involved = allCandidates.flatMap { listOf(it.left.profile, it.right.profile) }
            .distinctBy(AtomicProblemProfile::sourceId)

        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val scored = BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            val vectors = involved.associate { it.sourceId to provider.embed(ClusterSemanticTextV3.of(it)) }
            assertTrue(vectors.values.all { it.size == 1024 }, "BGE_M3_VECTOR_DIMENSION_INVALID")
            allCandidates.map { candidate ->
                val semantic = cosine(
                    vectors.getValue(candidate.left.profile.sourceId),
                    vectors.getValue(candidate.right.profile.sourceId)
                )
                val rule = ClusterCandidateRecallV2().similarity(candidate.left.profile, candidate.right.profile).toDouble()
                Scored(candidate, semantic, rule,
                    candidate.kind == CandidateKind.COMPATIBLE && semantic >= ACCEPT_THRESHOLD &&
                        rule + semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD)
            }
        }
        val compatibleScored = scored.filter { it.candidate.kind == CandidateKind.COMPATIBLE }
        val accept = compatibleScored.filter { it.semantic >= ACCEPT_THRESHOLD }
        val policyAccepted = accept.filter(Scored::policyAccepted)
        val signatureScored = scored.filter { it.candidate.kind == CandidateKind.SIGNATURE_CONFLICT }
        val signatureAccept = signatureScored.filter { it.semantic >= ACCEPT_THRESHOLD }
        val snapshot = Snapshot(
            freezeCursor = FREEZE_CURSOR,
            incrementalEndCursor = incrementalWindow.cursor,
            incrementalReadCount = incrementalRecords.size,
            incrementalActionableCount = incremental.size,
            referenceActionableCount = reference.size,
            compatibleGroupCount = compatibleScored.size,
            acceptGroupCount = accept.size,
            policyAcceptedCount = policyAccepted.size,
            missingTargetAcceptCount = accept.count { MissingField.TARGET in it.candidate.missingFields },
            missingActionAcceptCount = accept.count { MissingField.ACTION in it.candidate.missingFields },
            missingSignatureAcceptCount = accept.count { MissingField.ERROR_SIGNATURE in it.candidate.missingFields },
            signatureConflictGroupCount = signatureScored.size,
            signatureConflictAcceptCount = signatureAccept.size,
            involvedSourceCount = involved.size,
            digest = sha256(scored.joinToString("\n") {
                "${it.candidate.hash}|${it.candidate.kind}|${format(it.semantic)}|${format(it.rule)}|${it.policyAccepted}"
            })
        )

        exportReviewIfEnabled(accept + signatureAccept)
        if (discoveryMode()) {
            printSnapshot(snapshot)
            return
        }
        assertEquals(loadSnapshot(), snapshot, "CLUSTER_INCREMENTAL_VALIDATION_SNAPSHOT_DRIFT")
        val metrics = evaluateTruth(loadTruth(), scored)
        println("CLUSTER_INCREMENTAL_VALIDATION ${snapshot.summary()} tp=${metrics.tp} fp=${metrics.fp} " +
            "fn=${metrics.fn} tn=${metrics.tn} precision=${ratio(metrics.tp, metrics.tp + metrics.fp)} " +
            "recall=${ratio(metrics.tp, metrics.tp + metrics.fn)}")
    }

    private fun actionable(records: List<HistoricalClusterShadowInput>, extractor: AtomicProblemExtractorV3) = records
        .mapNotNull { row -> extractor.extract(row.classificationInput)
            .takeIf { it.status == ProblemClassificationStatus.ACTIONABLE }?.let { ProfileRow(it, row.currentClusterId) } }
        .sortedBy { it.profile.sourceId }

    private fun compatibleCandidates(incremental: List<ProfileRow>, reference: List<ProfileRow>): List<Candidate> =
        crossCandidates(incremental, reference).filter { (left, right) ->
            ProblemBoundaryPolicyV2().compare(left.profile, right.profile) == BoundaryEvidence.MATCH
        }.map { (left, right) -> Candidate(left, right, CandidateKind.COMPATIBLE, missingFields(left.profile, right.profile)) }
            .distinctBy(Candidate::hash)

    private fun signatureConflictCandidates(incremental: List<ProfileRow>, reference: List<ProfileRow>): List<Candidate> =
        crossCandidates(incremental, reference).filter { (left, right) ->
            !left.profile.errorSignature.isNullOrBlank() && !right.profile.errorSignature.isNullOrBlank() &&
                left.profile.errorSignature != right.profile.errorSignature
        }.map { (left, right) -> Candidate(left, right, CandidateKind.SIGNATURE_CONFLICT, emptySet()) }
            .distinctBy(Candidate::hash)

    private fun crossCandidates(incremental: List<ProfileRow>, reference: List<ProfileRow>) = incremental.flatMap { left ->
        reference.asSequence().filter { right ->
            left.profile.applicationKey == right.profile.applicationKey && left.profile.failureMode != null &&
                left.profile.failureMode == right.profile.failureMode &&
                normalizeSurface(left.profile.primaryClause) != normalizeSurface(right.profile.primaryClause)
        }.map { left to it }.toList()
    }

    private fun missingFields(left: AtomicProblemProfile, right: AtomicProblemProfile) = buildSet {
        if ((left.target == null) != (right.target == null)) add(MissingField.TARGET)
        if ((left.action == null) != (right.action == null)) add(MissingField.ACTION)
        if ((left.errorSignature == null) != (right.errorSignature == null)) add(MissingField.ERROR_SIGNATURE)
    }

    private fun readWindow(afterSourceId: Long, limit: Int): Window {
        val records = mutableListOf<HistoricalClusterShadowInput>()
        var cursor = afterSourceId
        while (records.size < limit) {
            val batch = input.read(cursor, minOf(HistoricalClusterShadowInputReader.MAX_BATCH_SIZE, limit - records.size))
            records += batch.records
            if (batch.nextSourceId <= cursor) {
                cursor = batch.nextSourceId
                break
            }
            cursor = batch.nextSourceId
        }
        return Window(records, cursor)
    }

    private fun exportReviewIfEnabled(rows: List<Scored>) {
        if (!System.getenv("CLUSTER_INCREMENTAL_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == REVIEW_EXPORT_PATH) { "CLUSTER_INCREMENTAL_REVIEW_PATH_NOT_ALLOWED" }
        val selected = rows.sortedWith(compareByDescending<Scored> { it.policyAccepted }
            .thenByDescending { it.semantic }.thenBy { it.candidate.hash })
            .groupBy { it.candidate.left.profile.failureMode.orEmpty() }
            .toSortedMap().values.flatMap { it.take(MAX_REVIEW_PER_MODE) }.take(MAX_REVIEW_GROUPS)
        val payload = buildString {
            appendLine("surface_pair_hash\tkind\tsemantic_score\trule_score\tpolicy_accepted\tmissing_fields\tfailure_mode\tleft_text\tright_text")
            selected.forEach { row -> appendLine(listOf(
                row.candidate.hash, row.candidate.kind, format(row.semantic), format(row.rule), row.policyAccepted,
                row.candidate.missingFields.sortedBy { it.name }.joinToString("+"),
                row.candidate.left.profile.failureMode.orEmpty(), safeReviewText(row.candidate.left.profile.primaryClause),
                safeReviewText(row.candidate.right.profile.primaryClause)
            ).joinToString("\t")) }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_INCREMENTAL_REVIEW selected=${selected.size} path=$REVIEW_EXPORT_PATH")
    }

    private fun safeReviewText(value: String?): String {
        val semanticOnly = ClusterSemanticTextV3.sanitizeClause(value)
        val masked = masking.mask(semanticOnly, evaluationKey = "cluster_incremental_validation", recordAudit = false).maskedText
        return HanLP.segment(masked).joinToString("") { term ->
            if (term.nature?.toString().orEmpty().startsWith("nr")) "[PERSON]" else term.word
        }.replace(Regex("[\\r\\n\\t]+"), " ").take(300).let { RESIDUAL_PII.replace(it, "[REDACTED]") }
            .also { require(!RESIDUAL_PII.containsMatchIn(it)) { "CLUSTER_INCREMENTAL_REVIEW_PII_RESIDUAL" } }
    }

    private fun loadSnapshot(): Snapshot {
        val values = checkNotNull(javaClass.classLoader.getResourceAsStream(FROZEN_RESOURCE)) {
            "CLUSTER_INCREMENTAL_SNAPSHOT_MISSING"
        }.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') } }
        fun value(key: String) = checkNotNull(values[key]) { "CLUSTER_INCREMENTAL_SNAPSHOT_FIELD_MISSING:$key" }
        return Snapshot(value("freeze_cursor").toLong(), value("incremental_end_cursor").toLong(),
            value("incremental_read_count").toInt(), value("incremental_actionable_count").toInt(),
            value("reference_actionable_count").toInt(), value("compatible_group_count").toInt(),
            value("accept_group_count").toInt(), value("policy_accepted_count").toInt(),
            value("missing_target_accept_count").toInt(), value("missing_action_accept_count").toInt(),
            value("missing_signature_accept_count").toInt(), value("signature_conflict_group_count").toInt(),
            value("signature_conflict_accept_count").toInt(), value("involved_source_count").toInt(), value("digest"))
    }

    private fun loadTruth(): Map<String, TruthLabel> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(GROUND_TRUTH_RESOURCE)
    ) { "CLUSTER_INCREMENTAL_GROUND_TRUTH_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line -> line.split('\t').let { columns ->
                require(columns.size == 3) { "CLUSTER_INCREMENTAL_TRUTH_ROW_INVALID" }
                columns[0] to TruthLabel.valueOf(columns[1])
            } }
    }

    private fun evaluateTruth(truth: Map<String, TruthLabel>, scored: List<Scored>): Metrics {
        val observed = scored.associateBy { it.candidate.hash }
        require(truth.keys.all(observed::containsKey)) { "CLUSTER_INCREMENTAL_TRUTH_CANDIDATE_DRIFT" }
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        truth.forEach { (hash, label) ->
            if (label == TruthLabel.AMBIGUOUS_EXCLUDED) return@forEach
            val predicted = observed.getValue(hash).policyAccepted
            when {
                label == TruthLabel.SAME_SYMPTOM && predicted -> tp++
                label == TruthLabel.SAME_SYMPTOM -> fn++
                label == TruthLabel.DIFFERENT_SYMPTOM && predicted -> fp++
                label == TruthLabel.DIFFERENT_SYMPTOM -> tn++
            }
        }
        return Metrics(tp, fp, fn, tn)
    }

    private fun printSnapshot(value: Snapshot) = println("CLUSTER_INCREMENTAL_FREEZE_DISCOVERY ${value.summary()}")
    private fun normalizeSurface(value: String?) = ClusterSemanticTextV3.sanitizeClause(value)
        .lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun ratio(numerator: Int, denominator: Int) = if (denominator == 0) "NA" else format(numerator.toDouble() / denominator)

    private data class ProfileRow(val profile: AtomicProblemProfile, val currentClusterId: Long?)
    private enum class CandidateKind { COMPATIBLE, SIGNATURE_CONFLICT }
    private enum class MissingField { TARGET, ACTION, ERROR_SIGNATURE }
    private enum class TruthLabel { SAME_SYMPTOM, DIFFERENT_SYMPTOM, AMBIGUOUS_EXCLUDED }
    private data class Candidate(
        val left: ProfileRow, val right: ProfileRow, val kind: CandidateKind, val missingFields: Set<MissingField>
    ) {
        val hash: String = sha256Static(listOf(
            ClusterSemanticTextV3.sanitizeClause(left.profile.primaryClause).lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), ""),
            ClusterSemanticTextV3.sanitizeClause(right.profile.primaryClause).lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
        ).sorted().joinToString("\u0000"))
    }
    private data class Scored(val candidate: Candidate, val semantic: Double, val rule: Double, val policyAccepted: Boolean)
    private data class Window(val records: List<HistoricalClusterShadowInput>, val cursor: Long)
    private data class Metrics(val tp: Int, val fp: Int, val fn: Int, val tn: Int)
    private data class Snapshot(
        val freezeCursor: Long, val incrementalEndCursor: Long, val incrementalReadCount: Int,
        val incrementalActionableCount: Int, val referenceActionableCount: Int, val compatibleGroupCount: Int,
        val acceptGroupCount: Int, val policyAcceptedCount: Int, val missingTargetAcceptCount: Int,
        val missingActionAcceptCount: Int, val missingSignatureAcceptCount: Int, val signatureConflictGroupCount: Int,
        val signatureConflictAcceptCount: Int, val involvedSourceCount: Int, val digest: String
    ) {
        fun summary() = "freeze_cursor=$freezeCursor incremental_end_cursor=$incrementalEndCursor " +
            "incremental_read_count=$incrementalReadCount incremental_actionable_count=$incrementalActionableCount " +
            "reference_actionable_count=$referenceActionableCount compatible_group_count=$compatibleGroupCount " +
            "accept_group_count=$acceptGroupCount policy_accepted_count=$policyAcceptedCount " +
            "missing_target_accept_count=$missingTargetAcceptCount missing_action_accept_count=$missingActionAcceptCount " +
            "missing_signature_accept_count=$missingSignatureAcceptCount signature_conflict_group_count=$signatureConflictGroupCount " +
            "signature_conflict_accept_count=$signatureConflictAcceptCount involved_source_count=$involvedSourceCount digest=$digest"
    }

    companion object {
        private const val FREEZE_CURSOR = 33_214L
        private const val MAX_SCAN_RECORDS = 50_000
        private const val MAX_INCREMENTAL_RECORDS = 5_000
        private const val ACCEPT_THRESHOLD = 0.82
        private const val SEMANTIC_WEIGHT = 0.20
        private const val LINK_THRESHOLD = 0.70
        private const val MAX_REVIEW_GROUPS = 100
        private const val MAX_REVIEW_PER_MODE = 10
        private const val REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-independent-incremental-review.tsv"
        private const val FROZEN_RESOURCE = "cluster/independent-incremental-freeze-v1.properties"
        private const val GROUND_TRUTH_RESOURCE = "cluster/real-independent-incremental-ground-truth-v1.tsv"
        private val RESIDUAL_PII = Regex(
            "(?i)(?:1[3-9][0-9]{9}|[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])" +
                "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
                "https?://|www\\.|[\\p{IsHan}]{2,20}(?:小学|中学|学院|幼儿园)|" +
                "(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班)"
        )
        private fun discoveryMode() = System.getenv("CLUSTER_INCREMENTAL_FREEZE_DISCOVERY").equals("true", true)
        private fun sha256Static(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
