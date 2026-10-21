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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.system.measureNanoTime

/**
 * 对已脱敏 PASS 历史记录做只读、弱监督的 BGE-M3 阈值审计。
 *
 * 测试日志只输出分层聚合值，不输出正文、标题、应用名或可反查的 source/cluster ID。这里的正负例来自
 * 现有簇关系、稳定症状身份和结构化硬冲突，不是假装成人工金标准；边界兼容但现有簇不同的 pair 只作为
 * UNLABELED 分布展示，禁止计入 precision/recall 或据此自动放宽阈值。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_BGE_CALIBRATION_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3HistoricalPairCalibrationTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val masking: MaskingPipeline
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `真实历史同义正例和硬负例只读校准且不泄露正文`() {
        val selectedPolicy = SemanticRecallPolicyV3()
        assertEquals("0.820000", selectedPolicy.acceptThreshold.toPlainString())
        assertEquals("0.680000", selectedPolicy.provisionalThreshold.toPlainString())
        assertEquals("0.200000", selectedPolicy.semanticWeight.toPlainString())
        val requested = System.getenv("CLUSTER_BGE_CALIBRATION_LIMIT")?.toIntOrNull() ?: DEFAULT_LIMIT
        require(requested in DEFAULT_LIMIT..MAX_LIMIT) { "CLUSTER_BGE_CALIBRATION_LIMIT_INVALID" }
        val records = readBatches(requested)
        val groundTruth = loadCompatibleGroundTruth()
        val surfaceGroupTruth = loadSurfaceGroupGroundTruth()
        val acceptHardNegativeTruth = loadAcceptHardNegativeGroundTruth()
        val extractor = AtomicProblemExtractorV3()
        val allProfiles = records.associate { record ->
            record.classificationInput.sourceId to extractor.extract(record.classificationInput)
        }
        val profiles = allProfiles.filterValues { it.status == ProblemClassificationStatus.ACTIONABLE }
        val currentClusters = records.associate { it.classificationInput.sourceId to it.currentClusterId }
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")

        BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            val inferenceMillis = mutableListOf<Double>()
            val vectors = profiles.mapValues { (_, profile) ->
                lateinit var vector: List<Float>
                val elapsed = measureNanoTime { vector = provider.embed(semanticText(profile)) }
                inferenceMillis += elapsed / 1_000_000.0
                vector
            }
            assertTrue(vectors.values.all { it.size == 1024 }, "BGE_M3_VECTOR_DIMENSION_INVALID")
            val strata = linkedMapOf<PairStratum, MutableList<PairScore>>()
            PairStratum.entries.forEach { strata[it] = mutableListOf() }
            val reviewCandidates = mutableListOf<ReviewCandidate>()
            val hardNegativeReviewCandidates = mutableListOf<ReviewCandidate>()
            val observedTruth = mutableSetOf<PairKey>()
            val observedTruthScores = mutableMapOf<PairKey, PairScore>()
            val effectiveGroundTruth = mutableMapOf<PairKey, GroundTruthLabel>()
            val hardNegativeTruthByHash = acceptHardNegativeTruth.associateBy(AcceptHardNegativeTruth::pairCaseHash)
            val observedHardNegativeProfiles = mutableMapOf<String, Pair<AtomicProblemProfile, AtomicProblemProfile>>()
            val observedSurfaceGroupCounts = mutableMapOf<String, Int>()
            val values = profiles.values.sortedBy(AtomicProblemProfile::sourceId)
            for (leftIndex in values.indices) for (rightIndex in leftIndex + 1 until values.size) {
                val left = values[leftIndex]
                val right = values[rightIndex]
                val key = PairKey.of(left.sourceId, right.sourceId)
                val surfacePairHash = reviewSurfacePairHash(left, right)
                groundTruth[pairCaseHash(key)]?.let { label -> effectiveGroundTruth[key] = label }
                val hardNegativeCaseHash = pairCaseHash(key, HARD_NEGATIVE_PAIR_HASH_DOMAIN)
                if (hardNegativeCaseHash in hardNegativeTruthByHash) {
                    observedHardNegativeProfiles.putIfAbsent(hardNegativeCaseHash, left to right)
                }
                var stratum = classifyPair(left, right, currentClusters, groundTruth) ?: continue
                if (stratum == PairStratum.UNLABELED_BOUNDARY_COMPATIBLE) {
                    val groupTruth = surfaceGroupTruth[surfacePairHash]
                    if (groupTruth != null) {
                        effectiveGroundTruth[key] = groupTruth.label
                        observedSurfaceGroupCounts.merge(surfacePairHash, 1, Int::plus)
                        stratum = when (groupTruth.label) {
                            GroundTruthLabel.SAME_SYMPTOM -> PairStratum.GOLD_COMPATIBLE_POSITIVE
                            GroundTruthLabel.DIFFERENT_SYMPTOM -> PairStratum.GOLD_COMPATIBLE_NEGATIVE
                            GroundTruthLabel.AMBIGUOUS_EXCLUDED -> PairStratum.GOLD_COMPATIBLE_AMBIGUOUS
                        }
                    }
                }
                val pairScore = PairScore(
                    semantic = cosine(vectors.getValue(left.sourceId), vectors.getValue(right.sourceId)),
                    rule = ClusterCandidateRecallV2().similarity(left, right).toDouble(),
                    exactIdentity = left.identityKey == right.identityKey
                )
                strata.getValue(stratum) += pairScore
                if (stratum == PairStratum.GOLD_COMPATIBLE_POSITIVE ||
                    stratum == PairStratum.GOLD_COMPATIBLE_NEGATIVE ||
                    stratum == PairStratum.GOLD_COMPATIBLE_AMBIGUOUS) {
                    observedTruth += key
                    observedTruthScores[key] = pairScore
                }
                if (stratum == PairStratum.UNLABELED_BOUNDARY_COMPATIBLE) {
                    reviewCandidates += ReviewCandidate(left, right, pairScore, stratum)
                }
                if (stratum.negative && pairScore.semantic >= CURRENT_ACCEPT_THRESHOLD) {
                    hardNegativeReviewCandidates += ReviewCandidate(left, right, pairScore, stratum)
                }
            }
            exportReviewRowsIfEnabled(reviewCandidates)
            exportHardNegativeRowsIfEnabled(hardNegativeReviewCandidates)
            println("CLUSTER_COMPATIBLE_SURFACE_GROUP_EXPANSION groups=${observedSurfaceGroupCounts.size} " +
                "pairs=${observedSurfaceGroupCounts.values.sum()} counts=" +
                observedSurfaceGroupCounts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" })
            if (freezeDiscoveryMode()) {
                println("CLUSTER_COMPATIBLE_SURFACE_GROUP_FREEZE_DRIFT expected_groups=${surfaceGroupTruth.size} " +
                    "expected_pairs=${surfaceGroupTruth.values.sumOf(SurfaceGroupTruth::expectedPairCount)} " +
                    "observed_groups=${observedSurfaceGroupCounts.size} observed_pairs=${observedSurfaceGroupCounts.values.sum()}")
            } else assertEquals(surfaceGroupTruth.mapValues { it.value.expectedPairCount }, observedSurfaceGroupCounts,
                "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_DRIFT")
            val expectedObserved = effectiveGroundTruth.keys.filter { key ->
                val left = profiles[key.left]
                val right = profiles[key.right]
                left != null && right != null && ProblemBoundaryPolicyV2().compare(left, right) == BoundaryEvidence.MATCH
            }.toSet()
            assertEquals(expectedObserved, observedTruth, "CLUSTER_COMPATIBLE_GROUND_TRUTH_DRIFT")
            val availableGroundTruth = effectiveGroundTruth.filterKeys { key ->
                profiles.containsKey(key.left) && profiles.containsKey(key.right)
            }
            val endToEnd = ClusterShadowClassifierV3().classify(records.map { it.classificationInput })
                .assignments.associateBy(ShadowClusterAssignment::sourceId)
            var groundTruthTp = 0
            var groundTruthFp = 0
            var groundTruthFn = 0
            availableGroundTruth.forEach { (key, label) ->
                if (label == GroundTruthLabel.AMBIGUOUS_EXCLUDED) return@forEach
                val left = endToEnd[key.left]
                val right = endToEnd[key.right]
                val predictedSame = left?.clusterKey != null && left.clusterKey == right?.clusterKey
                when {
                    label == GroundTruthLabel.SAME_SYMPTOM && predictedSame -> groundTruthTp++
                    label == GroundTruthLabel.SAME_SYMPTOM -> groundTruthFn++
                    predictedSame -> groundTruthFp++
                }
            }
            val evaluatedGroundTruth = availableGroundTruth.values.count { it != GroundTruthLabel.AMBIGUOUS_EXCLUDED }
            println("CLUSTER_COMPATIBLE_GROUND_TRUTH total=${availableGroundTruth.size} evaluated=$evaluatedGroundTruth " +
                "unavailable=${effectiveGroundTruth.size - availableGroundTruth.size} " +
                "positives=${availableGroundTruth.values.count { it == GroundTruthLabel.SAME_SYMPTOM }} " +
                "negatives=${availableGroundTruth.values.count { it == GroundTruthLabel.DIFFERENT_SYMPTOM }} " +
                "ambiguous=${availableGroundTruth.values.count { it == GroundTruthLabel.AMBIGUOUS_EXCLUDED }} " +
                "tp=$groundTruthTp fp=$groundTruthFp fn=$groundTruthFn " +
                "precision=${format(ratio(groundTruthTp, groundTruthTp + groundTruthFp))} " +
                "recall=${format(ratio(groundTruthTp, groundTruthTp + groundTruthFn))}")
            assertEquals(0, groundTruthFp)

            ACCEPT_THRESHOLDS.forEach { accept ->
                PROVISIONAL_THRESHOLDS.filter { it <= accept }.forEach { provisional ->
                    WEIGHTS.forEach { weight ->
                        var truePositive = 0
                        var falsePositive = 0
                        var falseNegative = 0
                        var provisionalPositive = 0
                        availableGroundTruth.forEach { (key, label) ->
                            if (label == GroundTruthLabel.AMBIGUOUS_EXCLUDED) return@forEach
                            val score = observedTruthScores[key]
                            val predictedSame = score?.let {
                                it.exactIdentity || it.semantic >= accept && it.rule + it.semantic * weight >= LINK_THRESHOLD
                            } ?: false
                            if (label == GroundTruthLabel.SAME_SYMPTOM) {
                                if (predictedSame) truePositive++ else falseNegative++
                                if (!predictedSame && score != null && score.semantic >= provisional) provisionalPositive++
                            } else if (predictedSame) falsePositive++
                        }
                        println("CLUSTER_COMPATIBLE_POLICY accept=${format(accept)} provisional=${format(provisional)} " +
                            "weight=${format(weight)} tp=$truePositive fp=$falsePositive fn=$falseNegative " +
                            "precision=${format(ratio(truePositive, truePositive + falsePositive))} " +
                            "recall=${format(ratio(truePositive, truePositive + falseNegative))} " +
                            "positive_provisional=$provisionalPositive")
                    }
                }
            }

            var acceptHardNegativeFalseMerges = 0
            var acceptHardNegativeConfirmed = 0
            var acceptModeCorrections = 0
            var acceptHardNegativeRetired = 0
            var acceptHardNegativeBelowAccept = 0
            val acceptHardNegativeScores = acceptHardNegativeTruth.mapNotNull { truth ->
                val pair = observedHardNegativeProfiles[truth.pairCaseHash]
                if (pair == null) {
                    require(truth.label == GroundTruthLabel.DIFFERENT_SYMPTOM) {
                        "CLUSTER_ACCEPT_HARD_NEGATIVE_POSITIVE_RETIRED"
                    }
                    acceptHardNegativeRetired++
                    return@mapNotNull null
                }
                val (left, right) = pair
                val score = cosine(vectors.getValue(left.sourceId), vectors.getValue(right.sourceId))
                if (score < CURRENT_ACCEPT_THRESHOLD) acceptHardNegativeBelowAccept++
                val boundary = ProblemBoundaryPolicyV2().compare(left, right)
                when (truth.label) {
                    GroundTruthLabel.DIFFERENT_SYMPTOM -> {
                        acceptHardNegativeConfirmed++
                        if (boundary != BoundaryEvidence.CONFLICT) acceptHardNegativeFalseMerges++
                    }
                    GroundTruthLabel.SAME_SYMPTOM -> {
                        acceptModeCorrections++
                        assertEquals(BoundaryEvidence.MATCH, boundary, "CLUSTER_ACCEPT_MODE_CORRECTION_NOT_MATCHED")
                    }
                    GroundTruthLabel.AMBIGUOUS_EXCLUDED -> error("CLUSTER_ACCEPT_HARD_NEGATIVE_AMBIGUOUS_NOT_ALLOWED")
                }
                score
            }.sorted()
            println("CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH reviewed=${acceptHardNegativeTruth.size} " +
                "confirmed_negatives=$acceptHardNegativeConfirmed mode_corrections=$acceptModeCorrections " +
                "retired_non_actionable_or_unavailable=$acceptHardNegativeRetired " +
                "below_current_accept_after_semantic_cleanup=$acceptHardNegativeBelowAccept " +
                "min=${format(acceptHardNegativeScores.first())} p50=${format(percentile(acceptHardNegativeScores, 0.50))} " +
                "max=${format(acceptHardNegativeScores.last())} boundary_false_merges=$acceptHardNegativeFalseMerges")
            assertEquals(58, acceptHardNegativeConfirmed + acceptHardNegativeRetired)
            assertEquals(2, acceptModeCorrections)
            assertEquals(0, acceptHardNegativeFalseMerges)

            val positives = (strata.getValue(PairStratum.POS_EXISTING_CLUSTER) +
                strata.getValue(PairStratum.POS_STABLE_IDENTITY) +
                strata.getValue(PairStratum.GOLD_COMPATIBLE_POSITIVE))
            val hardNegatives = PairStratum.entries.filter { it.negative }
                .flatMap { strata.getValue(it) }
            println("CLUSTER_BGE_CALIBRATION records=${records.size} actionable=${profiles.size} " +
                "positive_pairs=${positives.size} hard_negative_pairs=${hardNegatives.size} " +
                "unlabeled_pairs=${strata.getValue(PairStratum.UNLABELED_BOUNDARY_COMPATIBLE).size}")
            println("CLUSTER_BGE_CALIBRATION_PERF embeddings=${inferenceMillis.size} " +
                "total_ms=${format(inferenceMillis.sum())} avg_ms=${format(inferenceMillis.average())} " +
                "p50_ms=${format(percentile(inferenceMillis.sorted(), 0.50))} " +
                "p95_ms=${format(percentile(inferenceMillis.sorted(), 0.95))} max_ms=${format(inferenceMillis.max())}")
            PairStratum.entries.forEach { stratum ->
                println("CLUSTER_BGE_CALIBRATION_STRATUM name=${stratum.name} ${summary(strata.getValue(stratum))}")
            }
            THRESHOLDS.forEach { threshold ->
                val truePositive = positives.count { it.semantic >= threshold }
                val falsePositive = hardNegatives.count { it.semantic >= threshold }
                val precision = ratio(truePositive, truePositive + falsePositive)
                val recall = ratio(truePositive, positives.size)
                println("CLUSTER_BGE_CALIBRATION_RAW_WEAK_METRIC threshold=${String.format(Locale.ROOT, "%.2f", threshold)} " +
                    "tp=$truePositive fp=$falsePositive precision=${format(precision)} recall=${format(recall)}")
            }
            WEIGHTS.forEach { weight ->
                val acceptedPositive = positives.count { pair ->
                    pair.exactIdentity || pair.semantic >= CURRENT_ACCEPT_THRESHOLD &&
                        pair.rule + pair.semantic * weight >= LINK_THRESHOLD
                }
                val uncertainAccepted = strata.getValue(PairStratum.UNLABELED_BOUNDARY_COMPATIBLE).count { pair ->
                    pair.exactIdentity || pair.semantic >= CURRENT_ACCEPT_THRESHOLD &&
                        pair.rule + pair.semantic * weight >= LINK_THRESHOLD
                }
                val compatibleGoldFalsePositive = strata.getValue(PairStratum.GOLD_COMPATIBLE_NEGATIVE).count { pair ->
                    pair.semantic >= CURRENT_ACCEPT_THRESHOLD && pair.rule + pair.semantic * weight >= LINK_THRESHOLD
                }
                val policyPrecision = ratio(acceptedPositive, acceptedPositive + compatibleGoldFalsePositive)
                println("CLUSTER_BGE_CALIBRATION_POLICY accept=0.82 provisional=0.68 weight=${format(weight)} " +
                    "weak_positive_accepted=$acceptedPositive weak_positive_recall=${format(ratio(acceptedPositive, positives.size))} " +
                    "compatible_gold_fp=$compatibleGoldFalsePositive precision=${format(policyPrecision)} " +
                    "hard_boundary_fp=0 unlabeled_accepted=$uncertainAccepted")
            }

            // 这些门禁只约束评测真实性与覆盖，不能把弱监督指标升级为生产准确率结论。
            assertTrue(records.size >= 100, "CLUSTER_BGE_CALIBRATION_SAMPLE_TOO_SMALL")
            assertTrue(positives.isNotEmpty(), "CLUSTER_BGE_CALIBRATION_POSITIVE_PAIR_MISSING")
            assertTrue(hardNegatives.isNotEmpty(), "CLUSTER_BGE_CALIBRATION_HARD_NEGATIVE_MISSING")
        }
    }

    private fun readBatches(limit: Int): List<HistoricalClusterShadowInput> {
        val result = mutableListOf<HistoricalClusterShadowInput>()
        var cursor = 0L
        while (result.size < limit) {
            val batch = input.read(cursor, minOf(HistoricalClusterShadowInputReader.MAX_BATCH_SIZE, limit - result.size))
            result += batch.records
            if (!batch.mayHaveMore || batch.nextSourceId <= cursor) break
            cursor = batch.nextSourceId
        }
        return result
    }

    private fun classifyPair(
        left: AtomicProblemProfile,
        right: AtomicProblemProfile,
        currentClusters: Map<Long, Long?>,
        groundTruth: Map<String, GroundTruthLabel>
    ): PairStratum? {
        val sameApplication = left.applicationKey == right.applicationKey
        val sameFailureMode = left.failureMode != null && left.failureMode == right.failureMode
        val boundary = ProblemBoundaryPolicyV2().compare(left, right)
        val distinctSurface = normalizeSurface(left.primaryClause) != normalizeSurface(right.primaryClause)
        val sameCurrentCluster = currentClusters[left.sourceId] != null &&
            currentClusters[left.sourceId] == currentClusters[right.sourceId]
        if (boundary == BoundaryEvidence.MATCH) {
            when (groundTruth[pairCaseHash(PairKey.of(left.sourceId, right.sourceId))]) {
                GroundTruthLabel.SAME_SYMPTOM -> return PairStratum.GOLD_COMPATIBLE_POSITIVE
                GroundTruthLabel.DIFFERENT_SYMPTOM -> return PairStratum.GOLD_COMPATIBLE_NEGATIVE
                GroundTruthLabel.AMBIGUOUS_EXCLUDED -> return PairStratum.GOLD_COMPATIBLE_AMBIGUOUS
                null -> Unit
            }
        }
        if (sameCurrentCluster && boundary == BoundaryEvidence.MATCH && distinctSurface) {
            return PairStratum.POS_EXISTING_CLUSTER
        }
        if (left.identityKey == right.identityKey && distinctSurface) return PairStratum.POS_STABLE_IDENTITY
        if (!sameApplication) return PairStratum.NEG_CROSS_APPLICATION
        if (left.failureMode != null && right.failureMode != null && !sameFailureMode) {
            return PairStratum.NEG_FAILURE_MODE_CONFLICT
        }
        if (sameFailureMode && explicitConflict(left.errorSignature, right.errorSignature)) {
            return PairStratum.NEG_ERROR_SIGNATURE_CONFLICT
        }
        if (sameFailureMode && (explicitConflict(left.target, right.target) || explicitConflict(left.action, right.action))) {
            return PairStratum.NEG_SAME_MODE_TARGET_ACTION_CONFLICT
        }
        if (boundary == BoundaryEvidence.MATCH && !sameCurrentCluster) {
            return PairStratum.UNLABELED_BOUNDARY_COMPATIBLE
        }
        return null
    }

    private fun loadCompatibleGroundTruth(): Map<String, GroundTruthLabel> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(GROUND_TRUTH_RESOURCE)) {
            "CLUSTER_COMPATIBLE_GROUND_TRUTH_MISSING"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("pair_case_hash") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) { "CLUSTER_COMPATIBLE_GROUND_TRUTH_ROW_INVALID" }
                    columns[0] to GroundTruthLabel.valueOf(columns[1])
                }.toMap()
        }.also { truth ->
            require(truth.size == 60) { "CLUSTER_COMPATIBLE_GROUND_TRUTH_SIZE_INVALID" }
            require(truth.values.count { it == GroundTruthLabel.SAME_SYMPTOM } == 54) {
                "CLUSTER_COMPATIBLE_GROUND_TRUTH_POSITIVE_COUNT_INVALID"
            }
            require(truth.values.count { it == GroundTruthLabel.DIFFERENT_SYMPTOM } == 4) {
                "CLUSTER_COMPATIBLE_GROUND_TRUTH_NEGATIVE_COUNT_INVALID"
            }
            require(truth.values.count { it == GroundTruthLabel.AMBIGUOUS_EXCLUDED } == 2) {
                "CLUSTER_COMPATIBLE_GROUND_TRUTH_AMBIGUOUS_COUNT_INVALID"
            }
        }
    }

    private fun loadSurfaceGroupGroundTruth(): Map<String, SurfaceGroupTruth> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(SURFACE_GROUP_GROUND_TRUTH_RESOURCE)) {
            "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_MISSING"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 4) { "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_ROW_INVALID" }
                    columns[0] to SurfaceGroupTruth(
                        label = GroundTruthLabel.valueOf(columns[1]),
                        expectedPairCount = columns[2].toInt()
                    )
                }.toMap()
        }.also { truth ->
            require(truth.size == 17) { "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_SIZE_INVALID" }
            require(truth.values.all { it.label == GroundTruthLabel.SAME_SYMPTOM }) {
                "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_LABEL_INVALID"
            }
            require(truth.values.sumOf(SurfaceGroupTruth::expectedPairCount) == 581) {
                "CLUSTER_COMPATIBLE_SURFACE_GROUP_GROUND_TRUTH_PAIR_COUNT_INVALID"
            }
        }
    }

    private fun loadAcceptHardNegativeGroundTruth(): List<AcceptHardNegativeTruth> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_RESOURCE)) {
            "CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_MISSING"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("pair_case_hash") }
                .map { line ->
                    val columns = line.split('\t')
                    require(columns.size == 3) { "CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_ROW_INVALID" }
                    AcceptHardNegativeTruth(
                        pairCaseHash = columns[0],
                        label = GroundTruthLabel.valueOf(columns[1])
                    )
                }.toList()
        }.also { truth ->
            require(truth.size == 60 && truth.map(AcceptHardNegativeTruth::pairCaseHash).distinct().size == 60) {
                "CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_SIZE_INVALID"
            }
            require(truth.count { it.label == GroundTruthLabel.DIFFERENT_SYMPTOM } == 58) {
                "CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_NEGATIVE_COUNT_INVALID"
            }
            require(truth.count { it.label == GroundTruthLabel.SAME_SYMPTOM } == 2) {
                "CLUSTER_ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_CORRECTION_COUNT_INVALID"
            }
        }
    }

    private fun semanticText(profile: AtomicProblemProfile): String = ClusterSemanticTextV3.of(profile)

    /** 临时审阅文件只允许落在固定私有路径，且再次执行全规则脱敏和更激进的人名 NER。 */
    private fun safeReviewText(value: String?): String {
        val semanticOnly = ClusterSemanticTextV3.sanitizeClause(value)
        val masked = masking.mask(semanticOnly, evaluationKey = "cluster_pair_review", recordAudit = false).maskedText
        val aggressivelyRedacted = HanLP.segment(masked).joinToString("") { term ->
            if (term.nature?.toString().orEmpty().startsWith("nr")) "[PERSON]" else term.word
        }.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
        val residualRedacted = REVIEW_RESIDUAL_PII.replace(aggressivelyRedacted, "[REDACTED]")
        require(!REVIEW_RESIDUAL_PII.containsMatchIn(residualRedacted)) { "CLUSTER_PAIR_REVIEW_PII_RESIDUAL" }
        return residualRedacted
    }

    private fun exportReviewRowsIfEnabled(candidates: List<ReviewCandidate>) {
        if (!System.getenv("CLUSTER_BGE_REVIEW_EXPORT_ENABLED").equals("true", ignoreCase = true)) return
        val exportAll = System.getenv("CLUSTER_BGE_REVIEW_EXPORT_ALL")
            .equals("true", ignoreCase = true)
        val perBand = System.getenv("CLUSTER_BGE_REVIEW_PER_BAND")?.toIntOrNull() ?: DEFAULT_REVIEW_PER_BAND
        require(perBand in 1..MAX_REVIEW_PER_BAND) { "CLUSTER_PAIR_REVIEW_PER_BAND_INVALID" }
        val path = Path.of(System.getenv("CLUSTER_BGE_REVIEW_EXPORT_PATH") ?: REVIEW_EXPORT_PATH)
            .toAbsolutePath().normalize()
        require(path.toString() == REVIEW_EXPORT_PATH) { "CLUSTER_PAIR_REVIEW_EXPORT_PATH_NOT_ALLOWED" }
        val selected = if (exportAll) {
            candidates.sortedWith(compareByDescending<ReviewCandidate> { it.score.semantic }
                .thenBy { it.left.sourceId }.thenBy { it.right.sourceId })
                .map { it to ReviewScoreBand.of(it.score.semantic) }
        } else ReviewScoreBand.entries.flatMap { band ->
            val sourceCounts = mutableMapOf<Long, Int>()
            val surfacePairs = mutableSetOf<String>()
            candidates.asSequence()
                .filter { band.contains(it.score.semantic) }
                .sortedWith(compareBy<ReviewCandidate>(
                    { band.distanceFromDecisionBoundary(it.score.semantic) },
                    { it.left.sourceId },
                    { it.right.sourceId }
                ))
                .filter { candidate ->
                    val surfaceKey = reviewSurfacePairKey(candidate)
                    val sufficientlyDiverse = surfacePairs.add(surfaceKey) &&
                        sourceCounts.getOrDefault(candidate.left.sourceId, 0) < MAX_REVIEW_PAIRS_PER_SOURCE &&
                        sourceCounts.getOrDefault(candidate.right.sourceId, 0) < MAX_REVIEW_PAIRS_PER_SOURCE
                    if (sufficientlyDiverse) {
                        sourceCounts.merge(candidate.left.sourceId, 1, Int::plus)
                        sourceCounts.merge(candidate.right.sourceId, 1, Int::plus)
                    }
                    sufficientlyDiverse
                }
                .take(perBand)
                .map { it to band }
                .toList()
        }
        val payload = buildString {
            appendLine("left_source_id\tright_source_id\tsurface_pair_hash\tpair_stratum\tscore_band\tsemantic_score\trule_score\tfailure_mode\ttarget\taction\terror_signature\tleft_text\tright_text")
            selected.forEach { (candidate, band) ->
                appendLine(listOf(
                    candidate.left.sourceId,
                    candidate.right.sourceId,
                    reviewSurfacePairHash(candidate),
                    candidate.stratum.name,
                    band.name,
                    format(candidate.score.semantic),
                    format(candidate.score.rule),
                    candidate.left.failureMode.orEmpty(),
                    candidate.left.target.orEmpty(),
                    candidate.left.action.orEmpty(),
                    candidate.left.errorSignature.orEmpty(),
                    safeReviewText(candidate.left.primaryClause),
                    safeReviewText(candidate.right.primaryClause)
                ).joinToString("\t"))
            }
        }
        require(!REVIEW_RESIDUAL_PII.containsMatchIn(payload)) { "CLUSTER_PAIR_REVIEW_EXPORT_PII_RESIDUAL" }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        val counts = selected.groupingBy { it.second }.eachCount()
        println("CLUSTER_BGE_REVIEW_EXPORT candidates=${candidates.size} selected=${selected.size} all=$exportAll " +
            "bands=${ReviewScoreBand.entries.joinToString(",") { "${it.name}:${counts[it] ?: 0}" }} " +
            "path=$REVIEW_EXPORT_PATH")
    }

    private fun exportHardNegativeRowsIfEnabled(candidates: List<ReviewCandidate>) {
        if (!System.getenv("CLUSTER_BGE_HARD_NEGATIVE_EXPORT_ENABLED").equals("true", ignoreCase = true)) return
        val path = Path.of(HARD_NEGATIVE_REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == HARD_NEGATIVE_REVIEW_EXPORT_PATH) { "CLUSTER_HARD_NEGATIVE_EXPORT_PATH_NOT_ALLOWED" }
        val selected = PairStratum.entries.filter(PairStratum::negative).flatMap { stratum ->
            val surfacePairs = mutableSetOf<String>()
            candidates.asSequence().filter { it.stratum == stratum }
                .sortedWith(compareByDescending<ReviewCandidate> { it.score.semantic }
                    .thenBy { it.left.sourceId }.thenBy { it.right.sourceId })
                .filter { surfacePairs.add(reviewSurfacePairKey(it)) }
                .take(MAX_HARD_NEGATIVE_REVIEW_PER_STRATUM)
                .toList()
        }
        val payload = buildString {
            appendLine("left_source_id\tright_source_id\tsurface_pair_hash\tpair_stratum\tsemantic_score\trule_score\tfailure_mode\tleft_text\tright_text")
            selected.forEach { candidate ->
                appendLine(listOf(
                    candidate.left.sourceId,
                    candidate.right.sourceId,
                    reviewSurfacePairHash(candidate),
                    candidate.stratum.name,
                    format(candidate.score.semantic),
                    format(candidate.score.rule),
                    candidate.left.failureMode.orEmpty(),
                    safeReviewText(candidate.left.primaryClause),
                    safeReviewText(candidate.right.primaryClause)
                ).joinToString("\t"))
            }
        }
        require(!REVIEW_RESIDUAL_PII.containsMatchIn(payload)) { "CLUSTER_HARD_NEGATIVE_REVIEW_PII_RESIDUAL" }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        val counts = selected.groupingBy(ReviewCandidate::stratum).eachCount()
        println("CLUSTER_BGE_HARD_NEGATIVE_EXPORT candidates=${candidates.size} selected=${selected.size} " +
            "strata=${counts.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }} " +
            "path=$HARD_NEGATIVE_REVIEW_EXPORT_PATH")
    }

    private fun reviewSurfacePairKey(candidate: ReviewCandidate): String {
        val surfaces = listOf(
            normalizeSurface(candidate.left.primaryClause),
            normalizeSurface(candidate.right.primaryClause)
        ).sorted()
        return surfaces.joinToString("\u0000")
    }

    private fun reviewSurfacePairHash(candidate: ReviewCandidate): String =
        reviewSurfacePairHash(candidate.left, candidate.right)

    private fun reviewSurfacePairHash(left: AtomicProblemProfile, right: AtomicProblemProfile): String =
        MessageDigest.getInstance("SHA-256")
        .digest(listOf(normalizeSurface(left.primaryClause), normalizeSurface(right.primaryClause)).sorted()
            .joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun pairCaseHash(key: PairKey, domain: String = COMPATIBLE_PAIR_HASH_DOMAIN): String =
        MessageDigest.getInstance("SHA-256")
        .digest("$domain\u0000${key.left}\u0000${key.right}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun normalizeSurface(value: String?) = value.orEmpty().lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun explicitConflict(left: String?, right: String?) =
        left != null && right != null && !left.equals(right, ignoreCase = true)

    private fun cosine(left: List<Float>, right: List<Float>): Double =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)

    private fun summary(values: List<PairScore>): String {
        if (values.isEmpty()) return "count=0"
        val sorted = values.map(PairScore::semantic).sorted()
        return "count=${sorted.size} min=${format(sorted.first())} p05=${format(percentile(sorted, 0.05))} " +
            "p50=${format(percentile(sorted, 0.50))} p95=${format(percentile(sorted, 0.95))} max=${format(sorted.last())}"
    }

    private fun percentile(sorted: List<Double>, quantile: Double): Double {
        if (sorted.size == 1) return sorted.single()
        val position = quantile * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = ceil(position).toInt()
        val fraction = position - lower
        return sorted[lower] * (1 - fraction) + sorted[upper] * fraction
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)

    private enum class PairStratum(val negative: Boolean = false) {
        POS_EXISTING_CLUSTER,
        POS_STABLE_IDENTITY,
        GOLD_COMPATIBLE_POSITIVE,
        GOLD_COMPATIBLE_NEGATIVE(true),
        GOLD_COMPATIBLE_AMBIGUOUS,
        NEG_SAME_MODE_TARGET_ACTION_CONFLICT(true),
        NEG_ERROR_SIGNATURE_CONFLICT(true),
        NEG_FAILURE_MODE_CONFLICT(true),
        NEG_CROSS_APPLICATION(true),
        UNLABELED_BOUNDARY_COMPATIBLE
    }

    private data class PairScore(val semantic: Double, val rule: Double, val exactIdentity: Boolean)
    private data class ReviewCandidate(
        val left: AtomicProblemProfile,
        val right: AtomicProblemProfile,
        val score: PairScore,
        val stratum: PairStratum
    )
    private data class SurfaceGroupTruth(val label: GroundTruthLabel, val expectedPairCount: Int)
    private data class AcceptHardNegativeTruth(
        val pairCaseHash: String,
        val label: GroundTruthLabel
    )

    private enum class ReviewScoreBand(private val lowerInclusive: Double, private val upperExclusive: Double) {
        BELOW_PROVISIONAL(0.0, 0.68),
        PROVISIONAL(0.68, 0.82),
        ACCEPT(0.82, 0.90),
        HIGH_SIMILARITY(0.90, Double.POSITIVE_INFINITY);

        fun contains(score: Double) = score >= lowerInclusive && score < upperExclusive

        companion object {
            fun of(score: Double) = entries.first { it.contains(score) }
        }

        fun distanceFromDecisionBoundary(score: Double): Double = when (this) {
            BELOW_PROVISIONAL, PROVISIONAL -> kotlin.math.abs(score - 0.68)
            ACCEPT, HIGH_SIMILARITY -> kotlin.math.abs(score - 0.82)
        }
    }
    private data class PairKey(val left: Long, val right: Long) {
        companion object {
            fun of(first: Long, second: Long) = PairKey(minOf(first, second), maxOf(first, second))
        }
    }
    private enum class GroundTruthLabel { SAME_SYMPTOM, DIFFERENT_SYMPTOM, AMBIGUOUS_EXCLUDED }

    companion object {
        private const val DEFAULT_LIMIT = 500
        private const val MAX_LIMIT = 2_000
        private const val CURRENT_ACCEPT_THRESHOLD = 0.82
        private const val LINK_THRESHOLD = 0.70
        private const val REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-compatible-pair-review.tsv"
        private const val HARD_NEGATIVE_REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-hard-negative-review.tsv"
        private const val DEFAULT_REVIEW_PER_BAND = 50
        private const val MAX_REVIEW_PER_BAND = 100
        private const val MAX_REVIEW_PAIRS_PER_SOURCE = 3
        private const val MAX_HARD_NEGATIVE_REVIEW_PER_STRATUM = 50
        private const val COMPATIBLE_PAIR_HASH_DOMAIN = "compatible-pair-v3"
        private const val HARD_NEGATIVE_PAIR_HASH_DOMAIN = "accept-hard-negative-v2"
        private fun freezeDiscoveryMode() =
            System.getenv("CLUSTER_BGE_CALIBRATION_FREEZE_DISCOVERY").equals("true", true)
        private const val GROUND_TRUTH_RESOURCE = "cluster/real-compatible-pair-ground-truth-v2.tsv"
        private const val SURFACE_GROUP_GROUND_TRUTH_RESOURCE =
            "cluster/real-compatible-surface-group-ground-truth-v2.tsv"
        private const val ACCEPT_HARD_NEGATIVE_GROUND_TRUTH_RESOURCE =
            "cluster/real-accept-hard-negative-ground-truth-v1.tsv"
        private val REVIEW_RESIDUAL_PII = Regex(
            "(?i)(?:1[3-9][0-9]{9}|[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])" +
                "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
                "https?://|www\\.|[\\p{IsHan}]{2,20}(?:小学|中学|学院|幼儿园)|" +
                "(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班)"
        )
        private val THRESHOLDS = listOf(0.60, 0.64, 0.68, 0.72, 0.76, 0.80, 0.82, 0.84, 0.86, 0.88, 0.90)
        private val ACCEPT_THRESHOLDS = listOf(0.80, 0.82, 0.84, 0.86)
        private val PROVISIONAL_THRESHOLDS = listOf(0.64, 0.68, 0.72)
        private val WEIGHTS = listOf(0.20, 0.25, 0.30, 0.35)
    }
}
