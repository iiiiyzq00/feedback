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
import kotlin.math.abs

/**
 * 在校准 cursor 之后构造独立的真实历史验证集，专门审计结构字段单侧缺失时的高余弦误合并风险。
 * 冻结资产只保存边界、计数与摘要哈希，不保存正文、应用名、source/cluster ID 明细或个人信息。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_MISSING_FIELD_AUDIT_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3MissingFieldAdversarialAuditTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val masking: MaskingPipeline
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `缺失结构字段高余弦对抗审计使用独立冻结验证集`() {
        val calibration = readWindow(0, CALIBRATION_RECORDS)
        assertEquals(CALIBRATION_RECORDS, calibration.records.size, "CLUSTER_CALIBRATION_WINDOW_INCOMPLETE")
        val calibrationIds = calibration.records.map { it.classificationInput.sourceId }.toSet()
        val calibrationClusters = calibration.records.mapNotNull(HistoricalClusterShadowInput::currentClusterId).toSet()

        val heldOutWindow = readWindow(calibration.cursor, HELD_OUT_SCAN_RECORDS)
        val heldOut = heldOutWindow.records.filter { record ->
            record.classificationInput.sourceId !in calibrationIds &&
                (record.currentClusterId == null || record.currentClusterId !in calibrationClusters)
        }
        assertTrue(heldOut.isNotEmpty(), "CLUSTER_HELD_OUT_WINDOW_EMPTY")
        assertTrue(heldOut.none { it.classificationInput.sourceId in calibrationIds }, "CLUSTER_HELD_OUT_SOURCE_LEAKAGE")
        assertTrue(heldOut.mapNotNull(HistoricalClusterShadowInput::currentClusterId).none(calibrationClusters::contains),
            "CLUSTER_HELD_OUT_CLUSTER_LEAKAGE")

        val extractor = AtomicProblemExtractorV3()
        val windowProfiles = heldOutWindow.records.map { extractor.extract(it.classificationInput) }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .sortedBy(AtomicProblemProfile::sourceId)
        val profiles = heldOut.map { extractor.extract(it.classificationInput) }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .sortedBy(AtomicProblemProfile::sourceId)
        val windowStructuralCandidates = structuralCandidates(windowProfiles)
        val structuralCandidates = structuralCandidates(profiles)
        val truth = loadGroundTruth()
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val vectors: Map<Long, List<Float>>
        val scored: List<AdversarialPair>
        BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            vectors = profiles.associate { it.sourceId to provider.embed(semanticText(it)) }
            assertTrue(vectors.values.all { it.size == 1024 }, "BGE_M3_VECTOR_DIMENSION_INVALID")
            scored = structuralCandidates.map { candidate ->
                AdversarialPair(
                    candidate,
                    cosine(vectors.getValue(candidate.left.sourceId), vectors.getValue(candidate.right.sourceId)),
                    ClusterCandidateRecallV2().similarity(candidate.left, candidate.right).toDouble()
                )
            }
        }
        val acceptPairs = scored.filter { it.semantic >= ACCEPT_THRESHOLD }
        val independentGroups = acceptPairs.distinctBy(::surfacePairHash)
        val competitionCount = competitionCount(acceptPairs)
        val fieldCounts = MissingField.entries.associateWith { field ->
            independentGroups.count { field in it.candidate.missingFields }
        }
        val windowFieldGroups = MissingField.entries.associateWith { field ->
            windowStructuralCandidates.asSequence().filter { field in it.missingFields }
                .map(::surfacePairHash).distinct().count()
        }
        val truthMetrics = evaluateTruth(truth, profiles, vectors)

        val snapshot = FrozenSnapshot(
            calibrationCursor = calibration.cursor,
            heldOutEndCursor = heldOutWindow.cursor,
            heldOutReadCount = heldOutWindow.records.size,
            heldOutEligibleCount = heldOut.size,
            actionableCount = profiles.size,
            calibrationSourceDigest = sourceDigest(calibration.records),
            heldOutInputDigest = inputDigest(heldOut),
            heldOutClusterDigest = clusterDigest(heldOut),
            structuralPairCount = structuralCandidates.size,
            acceptPairCount = acceptPairs.size,
            independentGroupCount = independentGroups.size
        )
        if (discoveryMode()) printDiscoverySnapshot(snapshot) else assertEquals(
            loadFrozenSnapshot(), snapshot, "CLUSTER_MISSING_FIELD_HELD_OUT_SNAPSHOT_DRIFT"
        )
        exportReviewIfEnabled(independentGroups)

        println("CLUSTER_MISSING_FIELD_AUDIT calibration_records=${calibration.records.size} " +
            "held_out_read=${heldOutWindow.records.size} held_out_eligible=${heldOut.size} actionable=${profiles.size} " +
            "structural_pairs=${structuralCandidates.size} accept_pairs=${acceptPairs.size} " +
            "independent_groups=${independentGroups.size} competition_margin_pairs=$competitionCount " +
            "missing_target=${fieldCounts.getValue(MissingField.TARGET)} " +
            "missing_action=${fieldCounts.getValue(MissingField.ACTION)} " +
            "missing_signature=${fieldCounts.getValue(MissingField.ERROR_SIGNATURE)} " +
            "structural_${scoreSummary(scored.map(AdversarialPair::semantic))} " +
            "accept_${scoreSummary(acceptPairs.map(AdversarialPair::semantic))}")
        println("CLUSTER_MISSING_FIELD_COVERAGE post_calibration_actionable=${windowProfiles.size} " +
            "post_calibration_structural_pairs=${windowStructuralCandidates.size} " +
            "target_surface_groups=${windowFieldGroups.getValue(MissingField.TARGET)} " +
            "action_surface_groups=${windowFieldGroups.getValue(MissingField.ACTION)} " +
            "signature_surface_groups=${windowFieldGroups.getValue(MissingField.ERROR_SIGNATURE)} " +
            "isolated_actionable=${profiles.size} isolated_structural_pairs=${structuralCandidates.size}")
        println("CLUSTER_MISSING_FIELD_GROUND_TRUTH reviewed_groups=${truth.size} " +
            "negatives=${truthMetrics.negatives} ambiguous_excluded=${truthMetrics.ambiguous} " +
            "false_merges=${truthMetrics.falseMerges} true_rejects=${truthMetrics.trueRejects} " +
            "negative_specificity=${format(truthMetrics.specificity)} precision=NA recall=NA " +
            "max_erroneous_cluster_size=${if (truthMetrics.falseMerges == 0) 0 else 2}")
        assertTrue(structuralCandidates.isNotEmpty(), "CLUSTER_MISSING_FIELD_STRUCTURAL_PAIR_MISSING")
    }

    private fun readWindow(afterSourceId: Long, limit: Int): Window {
        val records = mutableListOf<HistoricalClusterShadowInput>()
        var cursor = afterSourceId
        while (records.size < limit) {
            val batch = input.read(cursor, minOf(HistoricalClusterShadowInputReader.MAX_BATCH_SIZE, limit - records.size))
            records += batch.records
            if (!batch.mayHaveMore || batch.nextSourceId <= cursor) {
                cursor = batch.nextSourceId
                break
            }
            cursor = batch.nextSourceId
        }
        return Window(records, cursor)
    }

    private fun missingFields(left: AtomicProblemProfile, right: AtomicProblemProfile): Set<MissingField> = buildSet {
        if ((left.target == null) != (right.target == null)) add(MissingField.TARGET)
        if ((left.action == null) != (right.action == null)) add(MissingField.ACTION)
        if ((left.errorSignature == null) != (right.errorSignature == null)) add(MissingField.ERROR_SIGNATURE)
    }

    private fun structuralCandidates(profiles: List<AtomicProblemProfile>): List<StructuralCandidate> = buildList {
        for (leftIndex in profiles.indices) for (rightIndex in leftIndex + 1 until profiles.size) {
            val left = profiles[leftIndex]
            val right = profiles[rightIndex]
            if (left.applicationKey != right.applicationKey || left.failureMode == null ||
                left.failureMode != right.failureMode || normalizeSurface(left.primaryClause) == normalizeSurface(right.primaryClause)) {
                continue
            }
            val missing = missingFields(left, right)
            if (missing.isEmpty() || ProblemBoundaryPolicyV2().compare(left, right) != BoundaryEvidence.MATCH) continue
            add(StructuralCandidate(left, right, missing))
        }
    }

    private fun competitionCount(pairs: List<AdversarialPair>): Int = pairs
        .flatMap { pair -> listOf(pair.candidate.left.sourceId to pair, pair.candidate.right.sourceId to pair) }
        .groupBy({ it.first }, { it.second.rule + it.second.semantic * SEMANTIC_WEIGHT })
        .values.count { scores ->
            val top = scores.sortedDescending()
            top.size > 1 && abs(top[0] - top[1]) <= COMPETITION_MARGIN
        }

    private fun loadFrozenSnapshot(): FrozenSnapshot {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(FROZEN_RESOURCE)) {
            "CLUSTER_MISSING_FIELD_HELD_OUT_SNAPSHOT_MISSING"
        }
        val values = stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
        }
        fun value(name: String) = checkNotNull(values[name]) { "CLUSTER_MISSING_FIELD_SNAPSHOT_FIELD_MISSING:$name" }
        return FrozenSnapshot(
            value("calibration_cursor").toLong(), value("held_out_end_cursor").toLong(),
            value("held_out_read_count").toInt(), value("held_out_eligible_count").toInt(),
            value("actionable_count").toInt(), value("calibration_source_digest"), value("held_out_input_digest"),
            value("held_out_cluster_digest"), value("structural_pair_count").toInt(),
            value("accept_pair_count").toInt(), value("independent_group_count").toInt()
        )
    }

    private fun loadGroundTruth(): Map<String, GroundTruthLabel> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(GROUND_TRUTH_RESOURCE)) {
            "CLUSTER_MISSING_FIELD_GROUND_TRUTH_MISSING"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
                .associate { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) { "CLUSTER_MISSING_FIELD_GROUND_TRUTH_ROW_INVALID" }
                    columns[0] to GroundTruthLabel.valueOf(columns[1])
                }
        }.also { truth ->
            require(truth.size == 14) { "CLUSTER_MISSING_FIELD_GROUND_TRUTH_SIZE_INVALID" }
            require(truth.values.count { it == GroundTruthLabel.DIFFERENT_SYMPTOM } == 1) {
                "CLUSTER_MISSING_FIELD_GROUND_TRUTH_NEGATIVE_COUNT_INVALID"
            }
            require(truth.values.count { it == GroundTruthLabel.AMBIGUOUS_EXCLUDED } == 13) {
                "CLUSTER_MISSING_FIELD_GROUND_TRUTH_AMBIGUOUS_COUNT_INVALID"
            }
        }
    }

    private fun evaluateTruth(
        truth: Map<String, GroundTruthLabel>,
        profiles: List<AtomicProblemProfile>,
        vectors: Map<Long, List<Float>>
    ): TruthMetrics {
        val found = mutableMapOf<String, MutableList<TruthScore>>()
        for (leftIndex in profiles.indices) for (rightIndex in leftIndex + 1 until profiles.size) {
            val left = profiles[leftIndex]
            val right = profiles[rightIndex]
            val hash = surfacePairHash(left, right)
            if (hash !in truth || left.applicationKey != right.applicationKey) continue
            val semantic = cosine(vectors.getValue(left.sourceId), vectors.getValue(right.sourceId))
            val rule = ClusterCandidateRecallV2().similarity(left, right).toDouble()
            val boundary = ProblemBoundaryPolicyV2().compare(left, right)
            val predictedSame = boundary == BoundaryEvidence.MATCH && semantic >= ACCEPT_THRESHOLD &&
                rule + semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD
            found.getOrPut(hash, ::mutableListOf) += TruthScore(semantic, boundary, predictedSame)
        }
        assertEquals(truth.keys, found.keys, "CLUSTER_MISSING_FIELD_GROUND_TRUTH_SURFACE_DRIFT")
        assertTrue(found.all { (hash, scores) ->
            scores.any { it.semantic >= ACCEPT_THRESHOLD } ||
                truth.getValue(hash) == GroundTruthLabel.DIFFERENT_SYMPTOM &&
                scores.any { it.boundary == BoundaryEvidence.CONFLICT }
        }, "CLUSTER_MISSING_FIELD_GROUND_TRUTH_SCORE_OR_CORRECTION_DRIFT")
        val negatives = truth.filterValues { it == GroundTruthLabel.DIFFERENT_SYMPTOM }.keys
        val falseMerges = negatives.count { hash ->
            found.getValue(hash).filter { it.semantic >= ACCEPT_THRESHOLD }.any(TruthScore::predictedSame)
        }
        return TruthMetrics(negatives.size, truth.values.count { it == GroundTruthLabel.AMBIGUOUS_EXCLUDED },
            falseMerges, negatives.size - falseMerges)
    }

    private fun printDiscoverySnapshot(snapshot: FrozenSnapshot) {
        println("CLUSTER_MISSING_FIELD_FREEZE_DISCOVERY " + listOf(
            "calibration_cursor=${snapshot.calibrationCursor}", "held_out_end_cursor=${snapshot.heldOutEndCursor}",
            "held_out_read_count=${snapshot.heldOutReadCount}", "held_out_eligible_count=${snapshot.heldOutEligibleCount}",
            "actionable_count=${snapshot.actionableCount}", "calibration_source_digest=${snapshot.calibrationSourceDigest}",
            "held_out_input_digest=${snapshot.heldOutInputDigest}", "held_out_cluster_digest=${snapshot.heldOutClusterDigest}",
            "structural_pair_count=${snapshot.structuralPairCount}", "accept_pair_count=${snapshot.acceptPairCount}",
            "independent_group_count=${snapshot.independentGroupCount}"
        ).joinToString(" "))
    }

    private fun exportReviewIfEnabled(groups: List<AdversarialPair>) {
        if (!System.getenv("CLUSTER_MISSING_FIELD_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == REVIEW_EXPORT_PATH) { "CLUSTER_MISSING_FIELD_REVIEW_PATH_NOT_ALLOWED" }
        val payload = buildString {
            appendLine("surface_pair_hash\tsemantic_score\trule_score\tmissing_fields\tfailure_mode\tleft_text\tright_text")
            groups.sortedWith(compareByDescending<AdversarialPair>(AdversarialPair::semantic)
                .thenBy(::surfacePairHash)).take(MAX_REVIEW_GROUPS).forEach { pair ->
                appendLine(listOf(
                    surfacePairHash(pair), format(pair.semantic), format(pair.rule),
                    pair.candidate.missingFields.sortedBy(MissingField::name).joinToString(","),
                    pair.candidate.left.failureMode.orEmpty(), safeReviewText(pair.candidate.left.primaryClause),
                    safeReviewText(pair.candidate.right.primaryClause)
                ).joinToString("\t"))
            }
        }
        require(!RESIDUAL_PII.containsMatchIn(payload)) { "CLUSTER_MISSING_FIELD_REVIEW_PII_RESIDUAL" }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_MISSING_FIELD_REVIEW_EXPORT groups=${groups.size} " +
            "selected=${minOf(groups.size, MAX_REVIEW_GROUPS)} path=$REVIEW_EXPORT_PATH")
    }

    private fun safeReviewText(value: String?): String {
        val semanticOnly = ClusterSemanticTextV3.sanitizeClause(value)
        val masked = masking.mask(semanticOnly, evaluationKey = "cluster_missing_field_review", recordAudit = false).maskedText
        val nerRedacted = HanLP.segment(masked).joinToString("") { term ->
            if (term.nature?.toString().orEmpty().startsWith("nr")) "[PERSON]" else term.word
        }.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        return RESIDUAL_PII.replace(nerRedacted, "[REDACTED]").also {
            require(!RESIDUAL_PII.containsMatchIn(it)) { "CLUSTER_MISSING_FIELD_REVIEW_PII_RESIDUAL" }
        }
    }

    private fun sourceDigest(records: List<HistoricalClusterShadowInput>) = sha256(
        records.map { it.classificationInput.sourceId }.sorted().joinToString(",")
    )

    private fun inputDigest(records: List<HistoricalClusterShadowInput>) = sha256(records.sortedBy {
        it.classificationInput.sourceId
    }.joinToString("\n") { record ->
        val item = record.classificationInput
        listOf(item.sourceId, item.applicationKey.orEmpty(), normalizeSurface(item.title),
            normalizeSurface(item.description), item.functionModule.orEmpty(), item.issueCategory.orEmpty()).joinToString("|")
    })

    private fun clusterDigest(records: List<HistoricalClusterShadowInput>) = sha256(records.sortedBy {
        it.classificationInput.sourceId
    }.joinToString("\n") { "${it.classificationInput.sourceId}|${it.currentClusterId ?: 0}" })

    private fun surfacePairHash(pair: AdversarialPair) = sha256(listOf(
        normalizeSurface(pair.candidate.left.primaryClause), normalizeSurface(pair.candidate.right.primaryClause)
    ).sorted().joinToString("\u0000"))

    private fun surfacePairHash(pair: StructuralCandidate) = sha256(listOf(
        normalizeSurface(pair.left.primaryClause), normalizeSurface(pair.right.primaryClause)
    ).sorted().joinToString("\u0000"))

    private fun surfacePairHash(left: AtomicProblemProfile, right: AtomicProblemProfile) = sha256(listOf(
        normalizeSurface(left.primaryClause), normalizeSurface(right.primaryClause)
    ).sorted().joinToString("\u0000"))

    private fun normalizeSurface(value: String?) = value.orEmpty().lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun semanticText(profile: AtomicProblemProfile) = ClusterSemanticTextV3.of(profile)

    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)

    private fun scoreSummary(scores: List<Double>): String = if (scores.isEmpty()) "score_count=0" else {
        val sorted = scores.sorted()
        "score_count=${sorted.size} min=${format(sorted.first())} p50=${format(sorted[sorted.size / 2])} max=${format(sorted.last())}"
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun discoveryMode() = System.getenv("CLUSTER_MISSING_FIELD_FREEZE_DISCOVERY").equals("true", true)

    private enum class MissingField { TARGET, ACTION, ERROR_SIGNATURE }
    private data class StructuralCandidate(
        val left: AtomicProblemProfile,
        val right: AtomicProblemProfile,
        val missingFields: Set<MissingField>
    )
    private data class AdversarialPair(val candidate: StructuralCandidate, val semantic: Double, val rule: Double)
    private data class TruthScore(val semantic: Double, val boundary: BoundaryEvidence, val predictedSame: Boolean)
    private data class TruthMetrics(val negatives: Int, val ambiguous: Int, val falseMerges: Int, val trueRejects: Int) {
        val specificity get() = if (negatives == 0) 0.0 else trueRejects.toDouble() / negatives
    }
    private enum class GroundTruthLabel { SAME_SYMPTOM, DIFFERENT_SYMPTOM, AMBIGUOUS_EXCLUDED }
    private data class Window(val records: List<HistoricalClusterShadowInput>, val cursor: Long)
    private data class FrozenSnapshot(
        val calibrationCursor: Long,
        val heldOutEndCursor: Long,
        val heldOutReadCount: Int,
        val heldOutEligibleCount: Int,
        val actionableCount: Int,
        val calibrationSourceDigest: String,
        val heldOutInputDigest: String,
        val heldOutClusterDigest: String,
        val structuralPairCount: Int,
        val acceptPairCount: Int,
        val independentGroupCount: Int
    )

    companion object {
        private const val CALIBRATION_RECORDS = 2_000
        // 已见 cluster 的复用率很高；扩大只读扫描窗后再过滤，不能通过放松隔离条件人为增加样本。
        private const val HELD_OUT_SCAN_RECORDS = 50_000
        private const val ACCEPT_THRESHOLD = 0.82
        private const val LINK_THRESHOLD = 0.70
        private const val SEMANTIC_WEIGHT = 0.20
        private const val COMPETITION_MARGIN = 0.03
        private const val MAX_REVIEW_GROUPS = 200
        private const val FROZEN_RESOURCE = "cluster/missing-field-held-out-freeze-v1.properties"
        private const val GROUND_TRUTH_RESOURCE = "cluster/real-missing-field-accept-ground-truth-v2.tsv"
        private const val REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-missing-field-review.tsv"
        private val RESIDUAL_PII = Regex(
            "(?i)(?:1[3-9][0-9]{9}|[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])" +
                "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
                "https?://|www\\.|[\\p{IsHan}]{2,20}(?:小学|中学|学院|幼儿园)|" +
                "(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班)"
        )
    }
}
