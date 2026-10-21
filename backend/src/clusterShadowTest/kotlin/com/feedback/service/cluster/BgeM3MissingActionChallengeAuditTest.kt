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
 * action 单侧缺失在严格 unseen-cluster 集中为零，因此另建时间/source/表述隔离 challenge 集。
 * current cluster 仅用于说明分布，绝不作为标签；该集合不能冒充严格 cluster-held-out 指标。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_MISSING_ACTION_AUDIT_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3MissingActionChallengeAuditTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val masking: MaskingPipeline
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `action 单侧缺失使用时间来源表述隔离的真实 challenge 集`() {
        val calibration = readWindow(0, CALIBRATION_RECORDS)
        val later = readWindow(calibration.cursor, POST_CALIBRATION_SCAN_RECORDS)
        assertEquals(CALIBRATION_RECORDS, calibration.records.size, "CLUSTER_ACTION_CALIBRATION_INCOMPLETE")
        assertTrue(later.records.none { item ->
            calibration.records.any { it.classificationInput.sourceId == item.classificationInput.sourceId }
        }, "CLUSTER_ACTION_SOURCE_LEAKAGE")

        val extractor = AtomicProblemExtractorV3()
        val calibrationProfiles = actionable(calibration.records, extractor)
        val laterProfiles = actionable(later.records, extractor)
        val calibrationSurfaces = calibrationProfiles.mapNotNull { normalizeSurface(it.primaryClause).takeIf(String::isNotBlank) }.toSet()
        val candidates = actionCandidates(laterProfiles).filter { candidate ->
            normalizeSurface(candidate.left.primaryClause) !in calibrationSurfaces &&
                normalizeSurface(candidate.right.primaryClause) !in calibrationSurfaces
        }
        val groups = candidates.groupBy(::surfacePairHash).mapValues { (_, rows) ->
            rows.minWith(compareBy<ActionCandidate> { it.left.sourceId }.thenBy { it.right.sourceId })
        }.toSortedMap()
        val involved = groups.values.flatMap { listOf(it.left, it.right) }.distinctBy(AtomicProblemProfile::sourceId)
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val scored = BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            val vectors = involved.associate { it.sourceId to provider.embed(semanticText(it)) }
            assertTrue(vectors.values.all { it.size == 1024 }, "BGE_M3_VECTOR_DIMENSION_INVALID")
            groups.map { (hash, candidate) ->
                ScoredActionGroup(
                    hash, candidate,
                    cosine(vectors.getValue(candidate.left.sourceId), vectors.getValue(candidate.right.sourceId)),
                    ClusterCandidateRecallV2().similarity(candidate.left, candidate.right).toDouble()
                )
            }
        }
        val accept = scored.filter { it.semantic >= ACCEPT_THRESHOLD }
        val metrics = evaluateTruth(loadTruth(), scored)
        val snapshot = Snapshot(
            calibrationCursor = calibration.cursor,
            laterEndCursor = later.cursor,
            laterReadCount = later.records.size,
            laterActionableCount = laterProfiles.size,
            unseenSurfaceGroupCount = groups.size,
            involvedSourceCount = involved.size,
            acceptGroupCount = accept.size,
            groupDigest = sha256(scored.joinToString("\n") { "${it.hash}|${format(it.semantic)}|${format(it.rule)}" })
        )
        if (discoveryMode()) printSnapshot(snapshot) else assertEquals(
            loadSnapshot(), snapshot, "CLUSTER_MISSING_ACTION_CHALLENGE_SNAPSHOT_DRIFT"
        )
        exportReviewIfEnabled(accept)
        println("CLUSTER_MISSING_ACTION_CHALLENGE calibration_records=${calibration.records.size} " +
            "post_calibration_read=${later.records.size} post_calibration_actionable=${laterProfiles.size} " +
            "unseen_surface_groups=${groups.size} involved_sources=${involved.size} accept_groups=${accept.size} " +
            "all_${summary(scored.map(ScoredActionGroup::semantic))} " +
            "accept_${summary(accept.map(ScoredActionGroup::semantic))}")
        println("CLUSTER_MISSING_ACTION_GROUND_TRUTH pair_tp=${metrics.pairTp} pair_fp=${metrics.pairFp} " +
            "pair_fn=${metrics.pairFn} pair_tn=${metrics.pairTn} pair_precision=${ratio(metrics.pairTp, metrics.pairTp + metrics.pairFp)} " +
            "pair_recall=${ratio(metrics.pairTp, metrics.pairTp + metrics.pairFn)} " +
            "case_groups=${metrics.caseGroups} group_tp=${metrics.groupTp} group_fp=${metrics.groupFp} " +
            "group_fn=${metrics.groupFn} group_tn=${metrics.groupTn} " +
            "group_precision=${ratio(metrics.groupTp, metrics.groupTp + metrics.groupFp)} " +
            "group_recall=${ratio(metrics.groupTp, metrics.groupTp + metrics.groupFn)} " +
            "ambiguous_excluded=${metrics.ambiguous} corrected_negative_pairs=${metrics.correctedNegatives}")
        assertTrue(groups.isNotEmpty(), "CLUSTER_MISSING_ACTION_CHALLENGE_EMPTY")
    }

    private fun actionable(records: List<HistoricalClusterShadowInput>, extractor: AtomicProblemExtractorV3) =
        records.map { extractor.extract(it.classificationInput) }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .sortedBy(AtomicProblemProfile::sourceId)

    private fun actionCandidates(profiles: List<AtomicProblemProfile>) = buildList {
        for (leftIndex in profiles.indices) for (rightIndex in leftIndex + 1 until profiles.size) {
            val left = profiles[leftIndex]
            val right = profiles[rightIndex]
            if (left.applicationKey != right.applicationKey || left.failureMode == null ||
                left.failureMode != right.failureMode || (left.action == null) == (right.action == null) ||
                !sameNullable(left.target, right.target) || !sameNullable(left.errorSignature, right.errorSignature) ||
                normalizeSurface(left.primaryClause) == normalizeSurface(right.primaryClause) ||
                ProblemBoundaryPolicyV2().compare(left, right) != BoundaryEvidence.MATCH) continue
            add(ActionCandidate(left, right))
        }
    }

    private fun sameNullable(left: String?, right: String?) =
        left == null && right == null || left != null && right != null && left.equals(right, true)

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

    private fun loadSnapshot(): Snapshot {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(FROZEN_RESOURCE)) {
            "CLUSTER_MISSING_ACTION_CHALLENGE_SNAPSHOT_MISSING"
        }
        val values = stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
        fun value(key: String) = checkNotNull(values[key]) { "CLUSTER_MISSING_ACTION_SNAPSHOT_FIELD_MISSING:$key" }
        return Snapshot(
            value("calibration_cursor").toLong(), value("later_end_cursor").toLong(),
            value("later_read_count").toInt(), value("later_actionable_count").toInt(),
            value("unseen_surface_group_count").toInt(), value("involved_source_count").toInt(),
            value("accept_group_count").toInt(), value("group_digest")
        )
    }

    private fun loadTruth(): Map<String, Truth> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(GROUND_TRUTH_RESOURCE)) {
            "CLUSTER_MISSING_ACTION_GROUND_TRUTH_MISSING"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
                .associate { line ->
                    val columns = line.split('\t')
                    require(columns.size == 4) { "CLUSTER_MISSING_ACTION_GROUND_TRUTH_ROW_INVALID" }
                    columns[0] to Truth(TruthLabel.valueOf(columns[1]), columns[2])
                }
        }.also { truth ->
            require(truth.size == 18) { "CLUSTER_MISSING_ACTION_GROUND_TRUTH_SIZE_INVALID" }
            require(truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 12)
            require(truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 3)
            require(truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 3)
        }
    }

    private fun evaluateTruth(truth: Map<String, Truth>, scored: List<ScoredActionGroup>): Metrics {
        val observed = scored.associateBy(ScoredActionGroup::hash)
        val missing = truth.keys - observed.keys
        require(missing.all { truth.getValue(it).label == TruthLabel.DIFFERENT_SYMPTOM }) {
            "CLUSTER_MISSING_ACTION_POSITIVE_OR_AMBIGUOUS_DRIFT"
        }
        fun predicted(hash: String): Boolean = observed[hash]?.let {
            it.semantic >= ACCEPT_THRESHOLD && it.rule + it.semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD
        } ?: false
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        truth.forEach { (hash, item) -> when (item.label) {
            TruthLabel.SAME_SYMPTOM -> if (predicted(hash)) tp++ else fn++
            TruthLabel.DIFFERENT_SYMPTOM -> if (predicted(hash)) fp++ else tn++
            TruthLabel.AMBIGUOUS_EXCLUDED -> Unit
        } }
        var groupTp = 0; var groupFp = 0; var groupFn = 0; var groupTn = 0
        val groups = truth.entries.groupBy { it.value.caseGroup }
        groups.values.forEach { rows ->
            val label = rows.map { it.value.label }.distinct().single()
            val same = rows.any { predicted(it.key) }
            when (label) {
                TruthLabel.SAME_SYMPTOM -> if (same) groupTp++ else groupFn++
                TruthLabel.DIFFERENT_SYMPTOM -> if (same) groupFp++ else groupTn++
                TruthLabel.AMBIGUOUS_EXCLUDED -> Unit
            }
        }
        return Metrics(tp, fp, fn, tn, groups.size, groupTp, groupFp, groupFn, groupTn,
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED }, missing.size)
    }

    private fun printSnapshot(value: Snapshot) = println("CLUSTER_MISSING_ACTION_FREEZE_DISCOVERY " + listOf(
        "calibration_cursor=${value.calibrationCursor}", "later_end_cursor=${value.laterEndCursor}",
        "later_read_count=${value.laterReadCount}", "later_actionable_count=${value.laterActionableCount}",
        "unseen_surface_group_count=${value.unseenSurfaceGroupCount}", "involved_source_count=${value.involvedSourceCount}",
        "accept_group_count=${value.acceptGroupCount}", "group_digest=${value.groupDigest}"
    ).joinToString(" "))

    private fun exportReviewIfEnabled(rows: List<ScoredActionGroup>) {
        if (!System.getenv("CLUSTER_MISSING_ACTION_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == REVIEW_EXPORT_PATH) { "CLUSTER_MISSING_ACTION_REVIEW_PATH_NOT_ALLOWED" }
        val payload = buildString {
            appendLine("surface_pair_hash\tsemantic_score\trule_score\tfailure_mode\ttarget\terror_signature\tleft_text\tright_text")
            rows.sortedWith(compareByDescending<ScoredActionGroup>(ScoredActionGroup::semantic).thenBy(ScoredActionGroup::hash))
                .take(MAX_REVIEW_GROUPS).forEach { row ->
                    appendLine(listOf(
                        row.hash, format(row.semantic), format(row.rule), row.candidate.left.failureMode.orEmpty(),
                        row.candidate.left.target ?: row.candidate.right.target.orEmpty(),
                        row.candidate.left.errorSignature ?: row.candidate.right.errorSignature.orEmpty(),
                        safeReviewText(row.candidate.left.primaryClause), safeReviewText(row.candidate.right.primaryClause)
                    ).joinToString("\t"))
                }
        }
        require(!RESIDUAL_PII.containsMatchIn(payload)) { "CLUSTER_MISSING_ACTION_REVIEW_PII_RESIDUAL" }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_MISSING_ACTION_REVIEW_EXPORT candidates=${rows.size} " +
            "selected=${minOf(rows.size, MAX_REVIEW_GROUPS)} path=$REVIEW_EXPORT_PATH")
    }

    private fun safeReviewText(value: String?): String {
        val semanticOnly = ClusterSemanticTextV3.sanitizeClause(value)
        val masked = masking.mask(semanticOnly, evaluationKey = "cluster_missing_action_review", recordAudit = false).maskedText
        val nerRedacted = HanLP.segment(masked).joinToString("") { term ->
            if (term.nature?.toString().orEmpty().startsWith("nr")) "[PERSON]" else term.word
        }.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        return RESIDUAL_PII.replace(nerRedacted, "[REDACTED]").also {
            require(!RESIDUAL_PII.containsMatchIn(it)) { "CLUSTER_MISSING_ACTION_REVIEW_PII_RESIDUAL" }
        }
    }

    private fun surfacePairHash(candidate: ActionCandidate) = sha256(listOf(
        normalizeSurface(candidate.left.primaryClause), normalizeSurface(candidate.right.primaryClause)
    ).sorted().joinToString("\u0000"))

    private fun normalizeSurface(value: String?) = value.orEmpty().lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun semanticText(profile: AtomicProblemProfile) = ClusterSemanticTextV3.of(profile)

    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun summary(scores: List<Double>) = if (scores.isEmpty()) "score_count=0" else {
        val sorted = scores.sorted()
        "score_count=${sorted.size} min=${format(sorted.first())} p50=${format(sorted[sorted.size / 2])} max=${format(sorted.last())}"
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun ratio(numerator: Int, denominator: Int) =
        if (denominator == 0) "NA" else format(numerator.toDouble() / denominator)
    private fun discoveryMode() = System.getenv("CLUSTER_MISSING_ACTION_FREEZE_DISCOVERY").equals("true", true)

    private data class ActionCandidate(val left: AtomicProblemProfile, val right: AtomicProblemProfile)
    private data class ScoredActionGroup(
        val hash: String, val candidate: ActionCandidate, val semantic: Double, val rule: Double
    )
    private data class Window(val records: List<HistoricalClusterShadowInput>, val cursor: Long)
    private data class Snapshot(
        val calibrationCursor: Long,
        val laterEndCursor: Long,
        val laterReadCount: Int,
        val laterActionableCount: Int,
        val unseenSurfaceGroupCount: Int,
        val involvedSourceCount: Int,
        val acceptGroupCount: Int,
        val groupDigest: String
    )
    private enum class TruthLabel { SAME_SYMPTOM, DIFFERENT_SYMPTOM, AMBIGUOUS_EXCLUDED }
    private data class Truth(val label: TruthLabel, val caseGroup: String)
    private data class Metrics(
        val pairTp: Int, val pairFp: Int, val pairFn: Int, val pairTn: Int,
        val caseGroups: Int, val groupTp: Int, val groupFp: Int, val groupFn: Int, val groupTn: Int,
        val ambiguous: Int, val correctedNegatives: Int
    )

    companion object {
        private const val CALIBRATION_RECORDS = 2_000
        private const val POST_CALIBRATION_SCAN_RECORDS = 50_000
        private const val ACCEPT_THRESHOLD = 0.82
        private const val SEMANTIC_WEIGHT = 0.20
        private const val LINK_THRESHOLD = 0.70
        private const val MAX_REVIEW_GROUPS = 100
        private const val FROZEN_RESOURCE = "cluster/missing-action-challenge-freeze-v1.properties"
        private const val GROUND_TRUTH_RESOURCE = "cluster/real-missing-action-accept-ground-truth-v1.tsv"
        private const val REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-missing-action-review.tsv"
        private val RESIDUAL_PII = Regex(
            "(?i)(?:1[3-9][0-9]{9}|[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])" +
                "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
                "https?://|www\\.|[\\p{IsHan}]{2,20}(?:小学|中学|学院|幼儿园)|" +
                "(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班)"
        )
    }
}
