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
 * 扩大审计到 calibration 之后全部边界兼容的真实 pair，避免缺失字段专项样本掩盖其它高余弦误合并。
 * current cluster 只用于风险排序，不参与标签；持久化冻结资产不得包含正文或记录级标识。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_POST_CALIBRATION_COMPATIBLE_AUDIT_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3PostCalibrationCompatibleAuditTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort,
    private val masking: MaskingPipeline
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `后校准真实兼容高余弦 pair 对抗审计`() {
        val calibration = readWindow(0, CALIBRATION_RECORDS)
        val later = readWindow(calibration.cursor, POST_CALIBRATION_SCAN_RECORDS)
        assertEquals(CALIBRATION_RECORDS, calibration.records.size, "CLUSTER_COMPATIBLE_AUDIT_CALIBRATION_INCOMPLETE")
        assertTrue(later.records.none { row ->
            calibration.records.any { it.classificationInput.sourceId == row.classificationInput.sourceId }
        }, "CLUSTER_COMPATIBLE_AUDIT_SOURCE_LEAKAGE")

        val extractor = AtomicProblemExtractorV3()
        val calibrationSurfaces = actionable(calibration.records, extractor)
            .mapNotNull { normalizeSurface(it.profile.primaryClause).takeIf(String::isNotBlank) }.toSet()
        val laterRows = actionable(later.records, extractor)
        val candidates = compatibleCandidates(laterRows).filter { candidate ->
            normalizeSurface(candidate.left.profile.primaryClause) !in calibrationSurfaces &&
                normalizeSurface(candidate.right.profile.primaryClause) !in calibrationSurfaces
        }.groupBy(::surfacePairHash).mapValues { (_, rows) ->
            rows.minWith(compareBy<Candidate> { it.left.profile.sourceId }.thenBy { it.right.profile.sourceId })
                .copy(occurrenceCount = rows.size)
        }.toSortedMap()
        val truth = loadTruth()
        val riskTruth = loadRiskTruth()
        val frozenRiskInventory = loadFrozenRiskInventory()
        val independentSample = loadIndependentSample(riskTruth.keys)
        val independentTruth = loadIndependentTruth(independentSample)
        val decisionDevelopmentSample = loadDecisionBalancedSample(riskTruth.keys + independentTruth.keys)
        val decisionDevelopmentTruth = loadDecisionBalancedTruth(decisionDevelopmentSample)
        val untouchedSample = loadDecisionBalancedSampleV2(
            riskTruth.keys + independentTruth.keys + decisionDevelopmentSample.keys
        )
        val untouchedTruth = loadDecisionBalancedTruthV2(untouchedSample)
        val untouchedSampleV3 = loadDecisionBalancedSampleV3(
            riskTruth.keys + independentTruth.keys + decisionDevelopmentSample.keys + untouchedSample.keys
        )
        val untouchedTruthV3 = loadDecisionBalancedTruthV3(untouchedSampleV3)
        assertTrue(riskTruth.keys.all(frozenRiskInventory::containsKey),
            "CLUSTER_POST_CALIBRATION_RISK_TRUTH_NOT_IN_FROZEN_INVENTORY")
        riskTruth.forEach { (hash, adjudicated) ->
            val frozen = frozenRiskInventory.getValue(hash)
            assertEquals(adjudicated.relations, frozen.relations,
                "CLUSTER_POST_CALIBRATION_RISK_TRUTH_RELATION_DRIFT:$hash")
            assertEquals(adjudicated.occurrenceCount, frozen.occurrenceCount,
                "CLUSTER_POST_CALIBRATION_RISK_TRUTH_OCCURRENCE_DRIFT:$hash")
        }
        val frozenEvaluationPairs = adjudicationPairs(
            laterRows, riskTruth.keys + independentTruth.keys + decisionDevelopmentSample.keys + untouchedSample.keys +
                untouchedSampleV3.keys
        )
        exportFrozenDevelopmentReviewIfEnabled(riskTruth + independentTruth, frozenEvaluationPairs)

        val involved = (candidates.values + frozenEvaluationPairs.values).flatMap { listOf(it.left.profile, it.right.profile) }
            .distinctBy(AtomicProblemProfile::sourceId)
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val (scored, frozenScores) = BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            val vectors = involved.associate { it.sourceId to provider.embed(semanticText(it)) }
            assertTrue(vectors.values.all { it.size == 1024 }, "BGE_M3_VECTOR_DIMENSION_INVALID")
            val candidateScores = candidates.map { (hash, candidate) ->
                val semantic = cosine(
                    vectors.getValue(candidate.left.profile.sourceId),
                    vectors.getValue(candidate.right.profile.sourceId)
                )
                val rule = ClusterCandidateRecallV2().similarity(candidate.left.profile, candidate.right.profile).toDouble()
                val eligibility = SemanticAutoAcceptEligibilityV4.reason(candidate.left.profile, candidate.right.profile)
                Scored(hash, candidate, semantic, rule, eligibility == SemanticAcceptEligibilityReason.ELIGIBLE &&
                    semantic >= ACCEPT_THRESHOLD && rule + semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD)
            }
            val adjudicationScores = frozenEvaluationPairs.map { (hash, candidate) ->
                val semantic = cosine(
                    vectors.getValue(candidate.left.profile.sourceId),
                    vectors.getValue(candidate.right.profile.sourceId)
                )
                val rule = ClusterCandidateRecallV2().similarity(candidate.left.profile, candidate.right.profile).toDouble()
                val boundary = ProblemBoundaryPolicyV2().compare(candidate.left.profile, candidate.right.profile)
                val eligibility = SemanticAutoAcceptEligibilityV4.reason(candidate.left.profile, candidate.right.profile)
                RiskScore(hash, semantic, rule, boundary, eligibility, boundary == BoundaryEvidence.MATCH &&
                    eligibility == SemanticAcceptEligibilityReason.ELIGIBLE &&
                    semantic >= ACCEPT_THRESHOLD && rule + semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD)
            }
            candidateScores to adjudicationScores
        }
        exportUntouchedReviewIfEnabled(untouchedSample, frozenEvaluationPairs, frozenScores)
        exportUntouchedV3ReviewIfEnabled(untouchedSampleV3, frozenEvaluationPairs, frozenScores)
        exportRetiredUntouchedReviewIfEnabled(untouchedSample, frozenEvaluationPairs.keys, later.records)
        val acceptBand = scored.filter { it.semantic >= ACCEPT_THRESHOLD }
        val policyAccepted = acceptBand.filter(Scored::policyAccepted)
        val crossClusterAccepted = policyAccepted.filter { it.candidate.crossCurrentCluster }
        exportIndependentCandidateInventoryIfEnabled(scored)
        val riskRows = crossClusterAccepted.map(::riskRow).sortedBy(RiskRow::hash)
        val currentRiskHashes = riskRows.map(RiskRow::hash).toSet()
        val currentRiskByHash = riskRows.associateBy(RiskRow::hash)
        independentSample.forEach { (hash, frozen) ->
            val current = currentRiskByHash[hash] ?: return@forEach
            assertEquals(frozen.relations, current.relations(),
                "CLUSTER_INDEPENDENT_POST_CALIBRATION_RELATION_DRIFT:$hash")
            assertEquals(frozen.occurrenceCount, current.occurrenceCount,
                "CLUSTER_INDEPENDENT_POST_CALIBRATION_OCCURRENCE_DRIFT:$hash")
        }
        val correctedHardNegatives = riskTruth.filterValues { item ->
            item.label == TruthLabel.DIFFERENT_SYMPTOM &&
                (item.reasonCode.startsWith("NETWORK_FAILURE_") || item.reasonCode.contains("READ_ALOUD_ERROR"))
        }.keys
        assertEquals(5, correctedHardNegatives.size, "CLUSTER_POST_CALIBRATION_CORRECTED_NEGATIVE_COUNT_DRIFT")
        assertTrue(correctedHardNegatives.none(currentRiskHashes::contains),
            "CLUSTER_POST_CALIBRATION_CORRECTED_NEGATIVE_STILL_ACCEPTED")
        val snapshot = Snapshot(
            calibration.cursor, later.cursor, later.records.size, laterRows.size, scored.size,
            involved.size, acceptBand.size, policyAccepted.size, crossClusterAccepted.size,
            sha256(scored.joinToString("\n") { "${it.hash}|${format(it.semantic)}|${format(it.rule)}|${it.policyAccepted}" })
        )
        if (discoveryMode()) printSnapshot(snapshot) else assertEquals(
            loadSnapshot(), snapshot, "CLUSTER_POST_CALIBRATION_COMPATIBLE_SNAPSHOT_DRIFT"
        )
        val metrics = evaluateTruth(truth, scored)
        val riskMetrics = evaluateRiskTruth(riskTruth, frozenScores)
        val independentMetrics = evaluateIndependentTruth(independentTruth, frozenScores)
        val decisionDevelopmentMetrics = evaluateIndependentTruth(decisionDevelopmentTruth, frozenScores)
        val untouchedMetrics = evaluateIndependentTruth(untouchedTruth, frozenScores)
        val untouchedMetricsV3 = evaluateIndependentTruth(untouchedTruthV3, frozenScores)
        exportRiskStrataIfEnabled(riskRows)
        exportReviewIfEnabled(acceptBand)
        println(
            "CLUSTER_POST_CALIBRATION_COMPATIBLE_AUDIT calibration_records=${calibration.records.size} " +
                "later_read=${later.records.size} later_actionable=${laterRows.size} compatible_groups=${scored.size} " +
                "involved_sources=${involved.size} accept_groups=${acceptBand.size} policy_accepted=${policyAccepted.size} " +
                "cross_cluster_accepted=${crossClusterAccepted.size} accept_${summary(acceptBand.map(Scored::semantic))}"
        )
        val riskStrataSummary = riskRows.groupingBy(RiskRow::stratumKey).eachCount()
            .entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        println("CLUSTER_POST_CALIBRATION_UNLABELED_RISK strata=$riskStrataSummary")
        println("CLUSTER_POST_CALIBRATION_FROZEN_RISK_INVENTORY groups=${frozenRiskInventory.size} " +
            "occurrences=${frozenRiskInventory.values.sumOf(FrozenRisk::occurrenceCount)} " +
            "target_single_missing=${frozenRiskInventory.values.count { it.relations[2] in SINGLE_MISSING }} " +
            "action_single_missing=${frozenRiskInventory.values.count { it.relations[3] in SINGLE_MISSING }} " +
            "signature_both_missing=${frozenRiskInventory.values.count { it.relations[4] == FieldRelation.BOTH_MISSING }}")
        println(
            "CLUSTER_POST_CALIBRATION_COMPATIBLE_GROUND_TRUTH tp=${metrics.tp} fp=${metrics.fp} " +
                "fn=${metrics.fn} tn=${metrics.tn} precision=${ratio(metrics.tp, metrics.tp + metrics.fp)} " +
                "recall=${ratio(metrics.tp, metrics.tp + metrics.fn)} case_groups=${metrics.caseGroups}"
        )
        println("CLUSTER_POST_CALIBRATION_RISK_GROUND_TRUTH reviewed=${riskTruth.size} " +
            "positives=${riskMetrics.positives} compatible_hard_negatives=${riskMetrics.negatives} " +
            "ambiguous_excluded=${riskMetrics.ambiguous} tp=${riskMetrics.tp} fp=${riskMetrics.fp} " +
            "fn=${riskMetrics.fn} tn=${riskMetrics.tn} precision=${ratio(riskMetrics.tp, riskMetrics.tp + riskMetrics.fp)} " +
            "recall=${ratio(riskMetrics.tp, riskMetrics.tp + riskMetrics.fn)} " +
            "positive_${summary(riskMetrics.positiveScores)} negative_${summary(riskMetrics.negativeScores)}")
        println("CLUSTER_INDEPENDENT_POST_CALIBRATION_STRESS reviewed=${independentTruth.size} " +
            "positives=${independentMetrics.positives} difficult_negatives=${independentMetrics.negatives} " +
            "ambiguous_excluded=${independentMetrics.ambiguous} tp=${independentMetrics.tp} " +
            "fp=${independentMetrics.fp} fn=${independentMetrics.fn} tn=${independentMetrics.tn} " +
            "accepted_precision=${ratio(independentMetrics.tp, independentMetrics.tp + independentMetrics.fp)} " +
            "recall=NA positive_${summary(independentMetrics.positiveScores)} " +
            "negative_${summary(independentMetrics.negativeScores)}")
        println("CLUSTER_DECISION_BALANCED_DEVELOPMENT reviewed=${decisionDevelopmentTruth.size} " +
            "positives=${decisionDevelopmentMetrics.positives} difficult_negatives=${decisionDevelopmentMetrics.negatives} " +
            "ambiguous_excluded=${decisionDevelopmentMetrics.ambiguous} tp=${decisionDevelopmentMetrics.tp} " +
            "fp=${decisionDevelopmentMetrics.fp} fn=${decisionDevelopmentMetrics.fn} tn=${decisionDevelopmentMetrics.tn} " +
            "precision=${ratio(decisionDevelopmentMetrics.tp, decisionDevelopmentMetrics.tp + decisionDevelopmentMetrics.fp)} " +
            "recall=${ratio(decisionDevelopmentMetrics.tp, decisionDevelopmentMetrics.tp + decisionDevelopmentMetrics.fn)}")
        println("CLUSTER_UNTOUCHED_DECISION_BALANCED_V2 reviewed=${untouchedTruth.size} " +
            "positives=${untouchedMetrics.positives} difficult_negatives=${untouchedMetrics.negatives} " +
            "ambiguous_excluded=${untouchedMetrics.ambiguous} tp=${untouchedMetrics.tp} fp=${untouchedMetrics.fp} " +
            "fn=${untouchedMetrics.fn} tn=${untouchedMetrics.tn} " +
            "precision=${ratio(untouchedMetrics.tp, untouchedMetrics.tp + untouchedMetrics.fp)} " +
            "recall=${ratio(untouchedMetrics.tp, untouchedMetrics.tp + untouchedMetrics.fn)}")
        println("CLUSTER_UNTOUCHED_DECISION_BALANCED_V3 reviewed=${untouchedTruthV3.size} " +
            "positives=${untouchedMetricsV3.positives} difficult_negatives=${untouchedMetricsV3.negatives} " +
            "ambiguous_excluded=${untouchedMetricsV3.ambiguous} tp=${untouchedMetricsV3.tp} " +
            "fp=${untouchedMetricsV3.fp} fn=${untouchedMetricsV3.fn} tn=${untouchedMetricsV3.tn} " +
            "precision=${ratio(untouchedMetricsV3.tp, untouchedMetricsV3.tp + untouchedMetricsV3.fp)} " +
            "recall=${ratio(untouchedMetricsV3.tp, untouchedMetricsV3.tp + untouchedMetricsV3.fn)}")
        printDecisionDiagnostics("RISK", riskTruth, frozenScores)
        printDecisionDiagnostics("INDEPENDENT", independentTruth, frozenScores)
        listOf(0.82, 0.84, 0.86, 0.88, 0.90).forEach { threshold ->
            val alternative = evaluateIndependentTruth(independentTruth, frozenScores, threshold)
            println("CLUSTER_INDEPENDENT_POST_CALIBRATION_THRESHOLD accept=${format(threshold)} weight=0.2000 " +
                "tp=${alternative.tp} fp=${alternative.fp} fn=${alternative.fn} tn=${alternative.tn} " +
                "precision=${ratio(alternative.tp, alternative.tp + alternative.fp)} " +
                "stress_recall=${ratio(alternative.tp, alternative.tp + alternative.fn)}")
        }
        assertEquals(0, metrics.fp, "CLUSTER_POST_CALIBRATION_COMPATIBLE_FALSE_MERGE")
        if (!discoveryMode()) {
            assertEquals(0, riskMetrics.fp, "CLUSTER_POST_CALIBRATION_RESIDUAL_RISK_DRIFT")
            assertEquals(IndependentMetrics(142, 64, 154, 124, 1, 18, 63,
                independentMetrics.positiveScores, independentMetrics.negativeScores), independentMetrics,
                "CLUSTER_INDEPENDENT_POST_CALIBRATION_CURRENT_POLICY_DRIFT")
            assertEquals(IndependentMetrics(81, 53, 316, 59, 0, 22, 53,
                decisionDevelopmentMetrics.positiveScores, decisionDevelopmentMetrics.negativeScores),
                decisionDevelopmentMetrics, "CLUSTER_DECISION_BALANCED_DEVELOPMENT_POLICY_DRIFT")
            assertEquals(IndependentMetrics(80, 47, 302, 58, 0, 22, 47,
                untouchedMetrics.positiveScores, untouchedMetrics.negativeScores), untouchedMetrics,
                "CLUSTER_UNTOUCHED_DECISION_BALANCED_V2_POLICY_DRIFT")
            assertEquals(IndependentMetrics(140, 156, 154, 110, 5, 30, 151,
                untouchedMetricsV3.positiveScores, untouchedMetricsV3.negativeScores), untouchedMetricsV3,
                "CLUSTER_UNTOUCHED_DECISION_BALANCED_V3_POLICY_DRIFT")
        }
        assertTrue(scored.isNotEmpty(), "CLUSTER_POST_CALIBRATION_COMPATIBLE_EMPTY")
    }

    private fun actionable(records: List<HistoricalClusterShadowInput>, extractor: AtomicProblemExtractorV3) = records.mapNotNull { row ->
        extractor.extract(row.classificationInput).takeIf { it.status == ProblemClassificationStatus.ACTIONABLE }
            ?.let { ProfileRow(it, row.currentClusterId) }
    }.sortedBy { it.profile.sourceId }

    private fun compatibleCandidates(rows: List<ProfileRow>): List<Candidate> = rows
        .groupBy { it.profile.applicationKey to it.profile.failureMode }
        .filterKeys { !it.first.isNullOrBlank() && it.second != null }
        .values.flatMap { group ->
            buildList {
                for (leftIndex in group.indices) for (rightIndex in leftIndex + 1 until group.size) {
                    val left = group[leftIndex]
                    val right = group[rightIndex]
                    if (normalizeSurface(left.profile.primaryClause) == normalizeSurface(right.profile.primaryClause) ||
                        ProblemBoundaryPolicyV2().compare(left.profile, right.profile) != BoundaryEvidence.MATCH) continue
                    add(Candidate(left, right, left.currentClusterId != null && right.currentClusterId != null &&
                        left.currentClusterId != right.currentClusterId))
                }
            }
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

    private fun adjudicationPairs(rows: List<ProfileRow>, hashes: Set<String>): Map<String, Candidate> {
        val found = mutableMapOf<String, Candidate>()
        rows.groupBy { it.profile.applicationKey }.values.forEach { group ->
            for (leftIndex in group.indices) for (rightIndex in leftIndex + 1 until group.size) {
                val left = group[leftIndex]
                val right = group[rightIndex]
                if (normalizeSurface(left.profile.primaryClause) == normalizeSurface(right.profile.primaryClause)) continue
                val candidate = Candidate(left, right, left.currentClusterId != null && right.currentClusterId != null &&
                    left.currentClusterId != right.currentClusterId)
                val hash = surfacePairHash(candidate)
                if (hash in hashes) found.putIfAbsent(hash, candidate)
            }
        }
        return found.toSortedMap()
    }

    private fun loadSnapshot(): Snapshot {
        val values = checkNotNull(javaClass.classLoader.getResourceAsStream(FROZEN_RESOURCE)) {
            "CLUSTER_POST_CALIBRATION_COMPATIBLE_SNAPSHOT_MISSING"
        }.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        }
        fun value(key: String) = checkNotNull(values[key]) { "CLUSTER_COMPATIBLE_SNAPSHOT_FIELD_MISSING:$key" }
        return Snapshot(
            value("calibration_cursor").toLong(), value("later_end_cursor").toLong(), value("later_read_count").toInt(),
            value("later_actionable_count").toInt(), value("compatible_group_count").toInt(),
            value("involved_source_count").toInt(), value("accept_group_count").toInt(),
            value("policy_accepted_count").toInt(), value("cross_cluster_accepted_count").toInt(), value("group_digest")
        )
    }

    private fun loadTruth(): Map<String, Truth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(GROUND_TRUTH_RESOURCE)
    ) { "CLUSTER_POST_CALIBRATION_COMPATIBLE_GROUND_TRUTH_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 4) { "CLUSTER_POST_CALIBRATION_COMPATIBLE_TRUTH_ROW_INVALID" }
                columns[0] to Truth(TruthLabel.valueOf(columns[1]), columns[2])
            }
    }.also { truth ->
        require(truth.size == 12 && truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 8 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 4) {
            "CLUSTER_POST_CALIBRATION_COMPATIBLE_TRUTH_COUNTS_INVALID"
        }
    }

    private fun loadRiskTruth(): Map<String, RiskTruth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(RISK_GROUND_TRUTH_RESOURCE)
    ) { "CLUSTER_POST_CALIBRATION_RISK_GROUND_TRUTH_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9) { "CLUSTER_POST_CALIBRATION_RISK_TRUTH_ROW_INVALID" }
                require(columns[2].matches(Regex("[A-Z0-9_]+"))) {
                    "CLUSTER_POST_CALIBRATION_RISK_REASON_CODE_INVALID"
                }
                val relations = columns.subList(3, 8).map(FieldRelation::valueOf)
                val occurrenceCount = columns[8].toInt()
                require(occurrenceCount > 0) { "CLUSTER_POST_CALIBRATION_RISK_OCCURRENCE_COUNT_INVALID" }
                columns[0] to RiskTruth(
                    TruthLabel.valueOf(columns[1]), columns[2], relations, occurrenceCount
                )
            }
    }.also { truth ->
        require(truth.size == 26 && truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 10 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 9 &&
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 7) {
            "CLUSTER_POST_CALIBRATION_RISK_TRUTH_COUNTS_INVALID"
        }
    }

    private fun loadFrozenRiskInventory(): Map<String, FrozenRisk> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(RISK_INVENTORY_RESOURCE)
    ) { "CLUSTER_POST_CALIBRATION_RISK_INVENTORY_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[1] == "UNLABELED_RISK" &&
                    columns[2] == "CROSS_CURRENT_CLUSTER_NOT_TRUTH") {
                    "CLUSTER_POST_CALIBRATION_RISK_INVENTORY_ROW_INVALID"
                }
                columns[0] to FrozenRisk(columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt())
            }
    }.also { inventory ->
        require(inventory.size == 885 && inventory.values.sumOf(FrozenRisk::occurrenceCount) == 1_114) {
            "CLUSTER_POST_CALIBRATION_RISK_INVENTORY_COUNTS_INVALID"
        }
        require(inventory.values.all { row ->
            row.occurrenceCount > 0 && row.relations[0] == FieldRelation.SAME &&
                row.relations[1] == FieldRelation.SAME && FieldRelation.CONFLICT !in row.relations
        }) { "CLUSTER_POST_CALIBRATION_RISK_INVENTORY_BOUNDARY_INVALID" }
    }

    private fun loadIndependentSample(developmentHashes: Set<String>): Map<String, FrozenRisk> {
        val expectedSizes = linkedMapOf(
            INDEPENDENT_SAMPLE_RESOURCE to 178,
            INDEPENDENT_AUGMENT_RESOURCE to 102,
            INDEPENDENT_CHALLENGE_RESOURCE to 80
        )
        val combined = linkedMapOf<String, FrozenRisk>()
        expectedSizes.forEach { (resource, expectedSize) ->
            val rows = loadFrozenRows(resource, "BLIND_SELECTED")
            require(rows.size == expectedSize) { "CLUSTER_INDEPENDENT_SAMPLE_SIZE_INVALID:$resource" }
            require(rows.keys.none(developmentHashes::contains)) {
                "CLUSTER_INDEPENDENT_SAMPLE_DEVELOPMENT_LEAKAGE:$resource"
            }
            rows.forEach { (hash, row) ->
                require(combined.put(hash, row) == null) { "CLUSTER_INDEPENDENT_SAMPLE_OVERLAP:$hash" }
            }
        }
        require(combined.size == 360 && combined.values.count { it.relations[2] in SINGLE_MISSING } == 74) {
            "CLUSTER_INDEPENDENT_SAMPLE_COUNTS_INVALID"
        }
        return combined
    }

    private fun loadIndependentTruth(sample: Map<String, FrozenRisk>): Map<String, RiskTruth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(INDEPENDENT_TRUTH_RESOURCE)
    ) { "CLUSTER_INDEPENDENT_POST_CALIBRATION_TRUTH_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[2].matches(Regex("[A-Z0-9_]+"))) {
                    "CLUSTER_INDEPENDENT_POST_CALIBRATION_TRUTH_ROW_INVALID"
                }
                columns[0] to RiskTruth(
                    TruthLabel.valueOf(columns[1]), columns[2],
                    columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { truth ->
        require(truth.keys == sample.keys && truth.size == 360 &&
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 142 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 64 &&
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 154) {
            "CLUSTER_INDEPENDENT_POST_CALIBRATION_TRUTH_COUNTS_INVALID"
        }
        truth.forEach { (hash, item) ->
            require(item.relations == sample.getValue(hash).relations &&
                item.occurrenceCount == sample.getValue(hash).occurrenceCount) {
                "CLUSTER_INDEPENDENT_POST_CALIBRATION_TRUTH_SAMPLE_DRIFT:$hash"
            }
        }
    }

    private fun loadFrozenRows(resource: String, expectedLabel: String): Map<String, FrozenRisk> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(resource)
    ) { "CLUSTER_INDEPENDENT_SAMPLE_MISSING:$resource" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[1] == expectedLabel &&
                    columns[2].matches(Regex("[A-Z0-9_]+")) && columns[0].matches(Regex("[0-9a-f]{64}"))) {
                    "CLUSTER_INDEPENDENT_SAMPLE_ROW_INVALID:$resource"
                }
                columns[0] to FrozenRisk(columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt())
            }
    }

    private fun loadDecisionBalancedSample(developmentHashes: Set<String>): Map<String, BlindCase> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_SAMPLE_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_SAMPLE_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[1] == "BLIND_SELECTED" &&
                    columns[0].matches(Regex("[0-9a-f]{64}")) &&
                    columns[2] in SHADOW10_SAMPLE_REASON_CODES) {
                    "CLUSTER_DECISION_BALANCED_SAMPLE_ROW_INVALID"
                }
                columns[0] to BlindCase(
                    columns[2], columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { sample ->
        require(sample.size == 450 && sample.keys.none(developmentHashes::contains) &&
            SHADOW10_SAMPLE_REASON_CODES.all { code -> sample.values.count { it.reasonCode == code } == 150 }) {
            "CLUSTER_DECISION_BALANCED_SAMPLE_COUNTS_INVALID"
        }
    }

    private fun loadDecisionBalancedTruth(sample: Map<String, BlindCase>): Map<String, RiskTruth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_TRUTH_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_TRUTH_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[2].matches(Regex("[A-Z0-9_]+"))) {
                    "CLUSTER_DECISION_BALANCED_TRUTH_ROW_INVALID"
                }
                columns[0] to RiskTruth(
                    TruthLabel.valueOf(columns[1]), columns[2],
                    columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { truth ->
        require(truth.keys == sample.keys && truth.size == 450 &&
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 81 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 53 &&
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 316) {
            "CLUSTER_DECISION_BALANCED_TRUTH_COUNTS_INVALID"
        }
        truth.forEach { (hash, item) ->
            val frozen = sample.getValue(hash)
            require(item.relations == frozen.relations && item.occurrenceCount == frozen.occurrenceCount) {
                "CLUSTER_DECISION_BALANCED_TRUTH_SAMPLE_DRIFT:$hash"
            }
        }
    }

    private fun loadDecisionBalancedSampleV2(developmentHashes: Set<String>): Map<String, BlindCase> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_SAMPLE_V2_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_SAMPLE_V2_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[1] == "BLIND_SELECTED" &&
                    columns[0].matches(Regex("[0-9a-f]{64}")) &&
                    columns[2] in SHADOW10_SAMPLE_REASON_CODES) {
                    "CLUSTER_DECISION_BALANCED_SAMPLE_V2_ROW_INVALID"
                }
                columns[0] to BlindCase(
                    columns[2], columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { sample ->
        require(sample.size == 429 && sample.keys.none(developmentHashes::contains) &&
            sample.values.count { it.reasonCode == "SHADOW10_ACCEPT_DECISION_SAMPLE" } == 150 &&
            sample.values.count { it.reasonCode == "SHADOW10_PROVISIONAL_DECISION_SAMPLE" } == 150 &&
            sample.values.count { it.reasonCode == "SHADOW10_REJECT_DECISION_SAMPLE" } == 129) {
            "CLUSTER_DECISION_BALANCED_SAMPLE_V2_COUNTS_INVALID"
        }
    }

    private fun loadDecisionBalancedTruthV2(sample: Map<String, BlindCase>): Map<String, RiskTruth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_TRUTH_V2_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_TRUTH_V2_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[2].matches(Regex("[A-Z0-9_]+"))) {
                    "CLUSTER_DECISION_BALANCED_TRUTH_V2_ROW_INVALID"
                }
                columns[0] to RiskTruth(
                    TruthLabel.valueOf(columns[1]), columns[2],
                    columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { truth ->
        require(truth.keys == sample.keys && truth.size == 429 &&
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 80 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 47 &&
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 302) {
            "CLUSTER_DECISION_BALANCED_TRUTH_V2_COUNTS_INVALID"
        }
        truth.forEach { (hash, item) ->
            val frozen = sample.getValue(hash)
            require(item.relations == frozen.relations && item.occurrenceCount == frozen.occurrenceCount) {
                "CLUSTER_DECISION_BALANCED_TRUTH_V2_SAMPLE_DRIFT:$hash"
            }
        }
    }

    private fun loadDecisionBalancedSampleV3(developmentHashes: Set<String>): Map<String, BlindCase> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_SAMPLE_V3_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_SAMPLE_V3_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[1] == "BLIND_SELECTED" &&
                    columns[0].matches(Regex("[0-9a-f]{64}")) &&
                    columns[2] in SHADOW11_SAMPLE_REASON_CODES) {
                    "CLUSTER_DECISION_BALANCED_SAMPLE_V3_ROW_INVALID"
                }
                columns[0] to BlindCase(
                    columns[2], columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { sample ->
        require(sample.size == 450 && sample.keys.none(developmentHashes::contains) &&
            SHADOW11_SAMPLE_REASON_CODES.all { code -> sample.values.count { it.reasonCode == code } == 150 }) {
            "CLUSTER_DECISION_BALANCED_SAMPLE_V3_COUNTS_INVALID"
        }
    }

    private fun loadDecisionBalancedTruthV3(sample: Map<String, BlindCase>): Map<String, RiskTruth> = checkNotNull(
        javaClass.classLoader.getResourceAsStream(DECISION_BALANCED_TRUTH_V3_RESOURCE)
    ) { "CLUSTER_DECISION_BALANCED_TRUTH_V3_MISSING" }.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("surface_pair_hash") }
            .associate { line ->
                val columns = line.split('\t')
                require(columns.size == 9 && columns[0].matches(Regex("[0-9a-f]{64}")) &&
                    columns[2].matches(Regex("[A-Z0-9_]+"))) {
                    "CLUSTER_DECISION_BALANCED_TRUTH_V3_ROW_INVALID"
                }
                columns[0] to RiskTruth(
                    TruthLabel.valueOf(columns[1]), columns[2],
                    columns.subList(3, 8).map(FieldRelation::valueOf), columns[8].toInt()
                )
            }
    }.also { truth ->
        require(truth.keys == sample.keys && truth.size == 450 &&
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM } == 140 &&
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM } == 156 &&
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED } == 154) {
            "CLUSTER_DECISION_BALANCED_TRUTH_V3_COUNTS_INVALID"
        }
        truth.forEach { (hash, item) ->
            val frozen = sample.getValue(hash)
            require(item.relations == frozen.relations && item.occurrenceCount == frozen.occurrenceCount) {
                "CLUSTER_DECISION_BALANCED_TRUTH_V3_SAMPLE_DRIFT:$hash"
            }
        }
    }

    private fun evaluateRiskTruth(truth: Map<String, RiskTruth>, scores: List<RiskScore>): RiskMetrics {
        val observed = scores.associateBy(RiskScore::hash)
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        truth.forEach { (hash, item) ->
            if (item.label == TruthLabel.AMBIGUOUS_EXCLUDED) return@forEach
            val predicted = observed[hash]?.policyAccepted == true
            when {
                item.label == TruthLabel.SAME_SYMPTOM && predicted -> tp++
                item.label == TruthLabel.SAME_SYMPTOM -> fn++
                predicted -> fp++
                else -> tn++
            }
        }
        return RiskMetrics(
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM },
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM },
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED }, tp, fp, fn, tn,
            truth.filterValues { it.label == TruthLabel.SAME_SYMPTOM }.keys.mapNotNull { observed[it]?.semantic },
            truth.filterValues { it.label == TruthLabel.DIFFERENT_SYMPTOM }.keys.mapNotNull { observed[it]?.semantic }
        )
    }

    private fun evaluateIndependentTruth(
        truth: Map<String, RiskTruth>,
        scored: List<RiskScore>,
        acceptThreshold: Double = ACCEPT_THRESHOLD
    ): IndependentMetrics {
        val observed = scored.associateBy(RiskScore::hash)
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        truth.forEach { (hash, item) ->
            if (item.label == TruthLabel.AMBIGUOUS_EXCLUDED) return@forEach
            val score = observed[hash]
            val predicted = score != null && score.boundary == BoundaryEvidence.MATCH &&
                score.eligibility == SemanticAcceptEligibilityReason.ELIGIBLE && score.semantic >= acceptThreshold &&
                score.rule + score.semantic * SEMANTIC_WEIGHT >= LINK_THRESHOLD
            when {
                item.label == TruthLabel.SAME_SYMPTOM && predicted -> tp++
                item.label == TruthLabel.SAME_SYMPTOM -> fn++
                predicted -> fp++
                else -> tn++
            }
        }
        return IndependentMetrics(
            truth.values.count { it.label == TruthLabel.SAME_SYMPTOM },
            truth.values.count { it.label == TruthLabel.DIFFERENT_SYMPTOM },
            truth.values.count { it.label == TruthLabel.AMBIGUOUS_EXCLUDED },
            tp, fp, fn, tn,
            truth.filterValues { it.label == TruthLabel.SAME_SYMPTOM }.keys.mapNotNull { observed[it]?.semantic },
            truth.filterValues { it.label == TruthLabel.DIFFERENT_SYMPTOM }.keys.mapNotNull { observed[it]?.semantic }
        )
    }

    private fun evaluateTruth(truth: Map<String, Truth>, scored: List<Scored>): Metrics {
        val observed = scored.associateBy(Scored::hash)
        val missingPositives = truth.filterValues { it.label == TruthLabel.SAME_SYMPTOM }.keys - observed.keys
        require(missingPositives.isEmpty()) {
            "CLUSTER_POST_CALIBRATION_COMPATIBLE_POSITIVE_DRIFT:${missingPositives.sorted().joinToString(",")}"
        }
        var tp = 0; var fp = 0; var fn = 0; var tn = 0
        truth.forEach { (hash, item) ->
            val predicted = observed[hash]?.policyAccepted == true
            when {
                item.label == TruthLabel.SAME_SYMPTOM && predicted -> tp++
                item.label == TruthLabel.SAME_SYMPTOM -> fn++
                predicted -> fp++
                else -> tn++
            }
        }
        return Metrics(tp, fp, fn, tn, truth.values.map(Truth::caseGroup).toSet().size)
    }

    private fun printDecisionDiagnostics(
        dataset: String,
        truth: Map<String, RiskTruth>,
        scores: List<RiskScore>
    ) {
        val observed = scores.associateBy(RiskScore::hash)
        val groups = truth.entries.filter { it.value.label != TruthLabel.AMBIGUOUS_EXCLUDED }
            .groupingBy { (hash, item) ->
                val score = observed[hash]
                val outcome = when {
                    item.label == TruthLabel.SAME_SYMPTOM && score?.policyAccepted == true -> "TP"
                    item.label == TruthLabel.SAME_SYMPTOM -> "FN"
                    score?.policyAccepted == true -> "FP"
                    else -> "TN"
                }
                "$outcome/${score?.eligibility?.name ?: "RETIRED"}/${item.reasonCode}"
            }.eachCount().entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
        println("CLUSTER_${dataset}_DECISION_DIAGNOSTICS groups=$groups")
    }

    private fun printSnapshot(value: Snapshot) = println(
        "CLUSTER_POST_CALIBRATION_COMPATIBLE_FREEZE_DISCOVERY calibration_cursor=${value.calibrationCursor} " +
            "later_end_cursor=${value.laterEndCursor} later_read_count=${value.laterReadCount} " +
            "later_actionable_count=${value.laterActionableCount} compatible_group_count=${value.compatibleGroupCount} " +
            "involved_source_count=${value.involvedSourceCount} accept_group_count=${value.acceptGroupCount} " +
            "policy_accepted_count=${value.policyAcceptedCount} cross_cluster_accepted_count=${value.crossClusterAcceptedCount} " +
            "group_digest=${value.groupDigest}"
    )

    private fun exportReviewIfEnabled(rows: List<Scored>) {
        if (!System.getenv("CLUSTER_POST_CALIBRATION_COMPATIBLE_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == REVIEW_EXPORT_PATH) { "CLUSTER_COMPATIBLE_REVIEW_PATH_NOT_ALLOWED" }
        val payload = buildString {
            appendLine("surface_pair_hash\tsemantic_score\trule_score\tpolicy_accepted\tcross_current_cluster\tfailure_mode\tleft_text\tright_text")
            reviewRows(rows).forEach { row ->
                    appendLine(listOf(
                        row.hash, format(row.semantic), format(row.rule), row.policyAccepted,
                        row.candidate.crossCurrentCluster, row.candidate.left.profile.failureMode.orEmpty(),
                        safeReviewText(row.candidate.left.profile.primaryClause),
                        safeReviewText(row.candidate.right.profile.primaryClause)
                    ).joinToString("\t"))
                }
        }
        // 正文已在 safeReviewText 中逐字段失败关闭；不要对 SHA-256 列套身份证正则，随机十六进制会产生伪命中。
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_POST_CALIBRATION_COMPATIBLE_REVIEW selected=${reviewRows(rows).size} path=$REVIEW_EXPORT_PATH")
    }

    private fun exportFrozenDevelopmentReviewIfEnabled(
        truth: Map<String, RiskTruth>,
        pairs: Map<String, Candidate>
    ) {
        if (!System.getenv("CLUSTER_FROZEN_DEVELOPMENT_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(FROZEN_DEVELOPMENT_REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == FROZEN_DEVELOPMENT_REVIEW_EXPORT_PATH) {
            "CLUSTER_FROZEN_DEVELOPMENT_REVIEW_PATH_NOT_ALLOWED"
        }
        val payload = buildString {
            appendLine("surface_pair_hash\tlabel\treason_code\teligibility\tleft_mode\tright_mode\tleft_text\tright_text")
            pairs.toSortedMap().forEach { (hash, pair) ->
                val item = truth[hash] ?: return@forEach
                appendLine(listOf(
                    hash, item.label.name, item.reasonCode,
                    SemanticAutoAcceptEligibilityV4.reason(pair.left.profile, pair.right.profile).name,
                    pair.left.profile.failureMode.orEmpty(), pair.right.profile.failureMode.orEmpty(),
                    safeReviewText(pair.left.profile.primaryClause), safeReviewText(pair.right.profile.primaryClause)
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_FROZEN_DEVELOPMENT_REVIEW_EXPORT rows=${pairs.size} path=$FROZEN_DEVELOPMENT_REVIEW_EXPORT_PATH")
    }

    private fun exportUntouchedReviewIfEnabled(
        sample: Map<String, BlindCase>,
        pairs: Map<String, Candidate>,
        scores: List<RiskScore>
    ) {
        if (!System.getenv("CLUSTER_UNTOUCHED_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(UNTOUCHED_REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == UNTOUCHED_REVIEW_EXPORT_PATH) { "CLUSTER_UNTOUCHED_REVIEW_PATH_NOT_ALLOWED" }
        val observed = scores.associateBy(RiskScore::hash)
        val payload = buildString {
            appendLine("surface_pair_hash\tshadow10_decision\tshadow11_decision\tsemantic_score\teligibility\tleft_mode\tright_mode\tleft_text\tright_text")
            sample.toSortedMap().forEach { (hash, item) ->
                val pair = pairs[hash] ?: return@forEach
                val score = observed[hash] ?: return@forEach
                appendLine(listOf(
                    hash, item.reasonCode.removePrefix("SHADOW10_").removeSuffix("_DECISION_SAMPLE"),
                    if (score.policyAccepted) "ACCEPT" else if (score.semantic >= PROVISIONAL_THRESHOLD) "PROVISIONAL" else "REJECT",
                    format(score.semantic), score.eligibility.name,
                    pair.left.profile.failureMode.orEmpty(), pair.right.profile.failureMode.orEmpty(),
                    safeReviewText(pair.left.profile.primaryClause), safeReviewText(pair.right.profile.primaryClause)
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_UNTOUCHED_REVIEW_EXPORT frozen=${sample.size} recovered=${payload.lineSequence().count() - 1} " +
            "path=$UNTOUCHED_REVIEW_EXPORT_PATH")
    }

    private fun exportRetiredUntouchedReviewIfEnabled(
        sample: Map<String, BlindCase>,
        recoveredHashes: Set<String>,
        records: List<HistoricalClusterShadowInput>
    ) {
        if (!System.getenv("CLUSTER_UNTOUCHED_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val missing = sample.keys - recoveredHashes
        if (missing.isEmpty()) return
        val surfaces = records.flatMap { row ->
            val application = row.classificationInput.applicationKey?.trim()?.lowercase()
                ?.takeIf(String::isNotBlank) ?: return@flatMap emptyList()
            rawReviewClauses(row.classificationInput).map { RawSurface(application, it) }
        }.distinctBy { it.application to normalizeSurface(it.text) }.groupBy(RawSurface::application)
        val found = linkedMapOf<String, Pair<String, String>>()
        surfaces.values.forEach { group ->
            for (leftIndex in group.indices) for (rightIndex in leftIndex + 1 until group.size) {
                val left = group[leftIndex].text
                val right = group[rightIndex].text
                if (normalizeSurface(left) == normalizeSurface(right)) continue
                val hash = sha256(listOf(normalizeSurface(left), normalizeSurface(right)).sorted().joinToString("\u0000"))
                if (hash in missing) found.putIfAbsent(hash, left to right)
            }
        }
        val path = Path.of(RETIRED_UNTOUCHED_REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == RETIRED_UNTOUCHED_REVIEW_EXPORT_PATH) {
            "CLUSTER_RETIRED_UNTOUCHED_REVIEW_PATH_NOT_ALLOWED"
        }
        val payload = buildString {
            appendLine("surface_pair_hash\tshadow10_decision\tshadow11_decision\teligibility\tleft_text\tright_text")
            found.toSortedMap().forEach { (hash, pair) ->
                appendLine(listOf(
                    hash, sample.getValue(hash).reasonCode.removePrefix("SHADOW10_").removeSuffix("_DECISION_SAMPLE"),
                    "REJECT", "RETIRED", safeReviewText(pair.first), safeReviewText(pair.second)
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_RETIRED_UNTOUCHED_REVIEW_EXPORT missing=${missing.size} recovered=${found.size} " +
            "path=$RETIRED_UNTOUCHED_REVIEW_EXPORT_PATH")
    }

    private fun exportUntouchedV3ReviewIfEnabled(
        sample: Map<String, BlindCase>,
        pairs: Map<String, Candidate>,
        scores: List<RiskScore>
    ) {
        if (!System.getenv("CLUSTER_UNTOUCHED_V3_REVIEW_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(UNTOUCHED_V3_REVIEW_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == UNTOUCHED_V3_REVIEW_EXPORT_PATH) {
            "CLUSTER_UNTOUCHED_V3_REVIEW_PATH_NOT_ALLOWED"
        }
        val observed = scores.associateBy(RiskScore::hash)
        val payload = buildString {
            appendLine("surface_pair_hash\tshadow11_decision\tsemantic_score\teligibility\tleft_mode\tright_mode\tleft_text\tright_text")
            sample.toSortedMap().forEach { (hash, item) ->
                val pair = pairs[hash] ?: return@forEach
                val score = observed[hash] ?: return@forEach
                appendLine(listOf(
                    hash, item.reasonCode.removePrefix("SHADOW11_").removeSuffix("_DECISION_SAMPLE"),
                    format(score.semantic), score.eligibility.name,
                    pair.left.profile.failureMode.orEmpty(), pair.right.profile.failureMode.orEmpty(),
                    safeReviewText(pair.left.profile.primaryClause), safeReviewText(pair.right.profile.primaryClause)
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_UNTOUCHED_V3_REVIEW_EXPORT frozen=${sample.size} recovered=${payload.lineSequence().count() - 1} " +
            "path=$UNTOUCHED_V3_REVIEW_EXPORT_PATH")
    }

    private fun rawReviewClauses(input: ClusterClassificationInput): List<String> =
        listOf(input.description.orEmpty(), input.title.orEmpty()).flatMap { value ->
            value.trim()
                .replace(Regex("^(?:【[^】]+】\\s*)+"), "")
                .replace(Regex("(?:客户描述|用户反馈|问题描述|现象)\\s*[:：-]*"), "")
                .replace(Regex("\\[[A-Z_]{2,32}]"), " ")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("(?:但是|但|然而|同时|另外|此外|而且)"), "。")
                .split(Regex("[。！？!?；;\\n]+"))
                .map(String::trim).filter(String::isNotBlank)
        }

    private fun exportRiskStrataIfEnabled(rows: List<RiskRow>) {
        if (!System.getenv("CLUSTER_POST_CALIBRATION_RISK_STRATA_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(RISK_STRATA_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == RISK_STRATA_EXPORT_PATH) { "CLUSTER_RISK_STRATA_PATH_NOT_ALLOWED" }
        val payload = buildString {
            appendLine("surface_pair_hash\tlabel\treason_code\tapplication_relation\tfailure_mode_relation\t" +
                "target_relation\taction_relation\terror_signature_relation\toccurrence_count")
            rows.forEach { row ->
                appendLine(listOf(
                    row.hash, "UNLABELED_RISK", "CROSS_CURRENT_CLUSTER_NOT_TRUTH",
                    row.application.name, row.failureMode.name, row.target.name, row.action.name,
                    row.errorSignature.name, row.occurrenceCount
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_POST_CALIBRATION_RISK_STRATA_EXPORT rows=${rows.size} path=$RISK_STRATA_EXPORT_PATH")
    }

    private fun exportIndependentCandidateInventoryIfEnabled(rows: List<Scored>) {
        if (!System.getenv("CLUSTER_INDEPENDENT_CANDIDATE_INVENTORY_EXPORT_ENABLED").equals("true", true)) return
        val path = Path.of(INDEPENDENT_CANDIDATE_INVENTORY_EXPORT_PATH).toAbsolutePath().normalize()
        require(path.toString() == INDEPENDENT_CANDIDATE_INVENTORY_EXPORT_PATH) {
            "CLUSTER_INDEPENDENT_CANDIDATE_INVENTORY_PATH_NOT_ALLOWED"
        }
        val payload = buildString {
            appendLine("surface_pair_hash\tdecision\treason_code\tapplication_relation\tfailure_mode_relation\t" +
                "target_relation\taction_relation\terror_signature_relation\toccurrence_count")
            rows.sortedBy(Scored::hash).forEach { scored ->
                val frozen = riskRow(scored)
                val decision = when {
                    scored.policyAccepted -> "ACCEPT"
                    scored.semantic >= PROVISIONAL_THRESHOLD -> "PROVISIONAL"
                    else -> "REJECT"
                }
                appendLine(listOf(
                    scored.hash, decision, "SHADOW10_PRECHANGE_UNTOUCHED_CANDIDATE",
                    frozen.application.name, frozen.failureMode.name, frozen.target.name, frozen.action.name,
                    frozen.errorSignature.name, frozen.occurrenceCount
                ).joinToString("\t"))
            }
        }
        Files.writeString(path, payload)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        println("CLUSTER_INDEPENDENT_CANDIDATE_INVENTORY_EXPORT rows=${rows.size} " +
            "path=$INDEPENDENT_CANDIDATE_INVENTORY_EXPORT_PATH")
    }

    /** 每个模式限额，避免一个高频症状的 O(n²) pair 组合淹没真正的困难案例。 */
    private fun reviewSample(rows: List<Scored>): List<Scored> {
        val priority = compareByDescending<Scored> { it.policyAccepted }
            .thenByDescending { it.candidate.crossCurrentCluster }
            .thenByDescending(Scored::semantic).thenBy(Scored::hash)
        return rows.groupBy { it.candidate.left.profile.failureMode.orEmpty() }
            .toSortedMap().values.flatMap { modeRows -> modeRows.sortedWith(priority).take(MAX_REVIEW_PER_MODE) }
            .sortedWith(priority).take(MAX_REVIEW_GROUPS)
    }

    private fun reviewRows(rows: List<Scored>): List<Scored> =
        if (System.getenv("CLUSTER_POST_CALIBRATION_COMPATIBLE_REVIEW_EXPORT_ALL").equals("true", true)) {
            rows.filter { it.policyAccepted && it.candidate.crossCurrentCluster }
                .sortedWith(compareByDescending<Scored>(Scored::semantic).thenBy(Scored::hash))
        } else reviewSample(rows)

    private fun riskRow(scored: Scored): RiskRow {
        val candidate = scored.candidate
        return RiskRow(
            scored.hash,
            relation(candidate.left.profile.applicationKey, candidate.right.profile.applicationKey),
            relation(candidate.left.profile.failureMode, candidate.right.profile.failureMode),
            relation(candidate.left.profile.target, candidate.right.profile.target),
            relation(candidate.left.profile.action, candidate.right.profile.action),
            relation(candidate.left.profile.errorSignature, candidate.right.profile.errorSignature),
            candidate.occurrenceCount
        )
    }

    private fun relation(left: String?, right: String?): FieldRelation = when {
        left == null && right == null -> FieldRelation.BOTH_MISSING
        left == null -> FieldRelation.LEFT_MISSING
        right == null -> FieldRelation.RIGHT_MISSING
        left.equals(right, true) -> FieldRelation.SAME
        else -> FieldRelation.CONFLICT
    }

    private fun safeReviewText(value: String?): String {
        val semanticOnly = ClusterSemanticTextV3.sanitizeClause(value)
        val masked = masking.mask(semanticOnly, evaluationKey = "cluster_post_calibration_review", recordAudit = false).maskedText
        return HanLP.segment(masked).joinToString("") { term ->
            if (term.nature?.toString().orEmpty().startsWith("nr")) "[PERSON]" else term.word
        }.replace(Regex("[\\r\\n\\t]+"), " ").take(300).let { RESIDUAL_PII.replace(it, "[REDACTED]") }
            .also { require(!RESIDUAL_PII.containsMatchIn(it)) { "CLUSTER_COMPATIBLE_REVIEW_PII_RESIDUAL" } }
    }

    private fun surfacePairHash(candidate: Candidate) = sha256(listOf(
        normalizeSurface(candidate.left.profile.primaryClause), normalizeSurface(candidate.right.profile.primaryClause)
    ).sorted().joinToString("\u0000"))

    private fun normalizeSurface(value: String?) = ClusterSemanticTextV3.sanitizeClause(value)
        .lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    private fun semanticText(profile: AtomicProblemProfile) = ClusterSemanticTextV3.of(profile)
    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
    private fun ratio(numerator: Int, denominator: Int) = if (denominator == 0) "NA" else format(numerator.toDouble() / denominator)
    private fun summary(values: List<Double>) = if (values.isEmpty()) "score_count=0" else values.sorted().let {
        "score_count=${it.size} min=${format(it.first())} p50=${format(it[it.size / 2])} max=${format(it.last())}"
    }

    private data class ProfileRow(val profile: AtomicProblemProfile, val currentClusterId: Long?)
    private data class Candidate(
        val left: ProfileRow,
        val right: ProfileRow,
        val crossCurrentCluster: Boolean,
        val occurrenceCount: Int = 1
    )
    private data class Scored(
        val hash: String,
        val candidate: Candidate,
        val semantic: Double,
        val rule: Double,
        val policyAccepted: Boolean
    )
    private data class Window(val records: List<HistoricalClusterShadowInput>, val cursor: Long)
    private enum class FieldRelation { SAME, LEFT_MISSING, RIGHT_MISSING, BOTH_MISSING, CONFLICT }
    private data class RiskRow(
        val hash: String,
        val application: FieldRelation,
        val failureMode: FieldRelation,
        val target: FieldRelation,
        val action: FieldRelation,
        val errorSignature: FieldRelation,
        val occurrenceCount: Int
    ) {
        fun stratumKey() = listOf(application, failureMode, target, action, errorSignature)
            .joinToString("/") { it.name }
        fun relations() = listOf(application, failureMode, target, action, errorSignature)
    }
    private data class Snapshot(
        val calibrationCursor: Long, val laterEndCursor: Long, val laterReadCount: Int, val laterActionableCount: Int,
        val compatibleGroupCount: Int, val involvedSourceCount: Int, val acceptGroupCount: Int,
        val policyAcceptedCount: Int, val crossClusterAcceptedCount: Int, val groupDigest: String
    )
    private enum class TruthLabel { SAME_SYMPTOM, DIFFERENT_SYMPTOM, AMBIGUOUS_EXCLUDED }
    private data class Truth(val label: TruthLabel, val caseGroup: String)
    private data class RiskTruth(
        val label: TruthLabel,
        val reasonCode: String,
        val relations: List<FieldRelation>,
        val occurrenceCount: Int
    )
    private data class FrozenRisk(val relations: List<FieldRelation>, val occurrenceCount: Int)
    private data class BlindCase(
        val reasonCode: String,
        val relations: List<FieldRelation>,
        val occurrenceCount: Int
    )
    private data class RawSurface(val application: String, val text: String)
    private data class RiskScore(
        val hash: String,
        val semantic: Double,
        val rule: Double,
        val boundary: BoundaryEvidence,
        val eligibility: SemanticAcceptEligibilityReason,
        val policyAccepted: Boolean
    )
    private data class Metrics(val tp: Int, val fp: Int, val fn: Int, val tn: Int, val caseGroups: Int)
    private data class RiskMetrics(
        val positives: Int, val negatives: Int, val ambiguous: Int,
        val tp: Int, val fp: Int, val fn: Int, val tn: Int,
        val positiveScores: List<Double>, val negativeScores: List<Double>
    )
    private data class IndependentMetrics(
        val positives: Int, val negatives: Int, val ambiguous: Int,
        val tp: Int, val fp: Int, val fn: Int, val tn: Int,
        val positiveScores: List<Double>, val negativeScores: List<Double>
    )

    companion object {
        private const val CALIBRATION_RECORDS = 2_000
        private const val POST_CALIBRATION_SCAN_RECORDS = 50_000
        private const val ACCEPT_THRESHOLD = 0.82
        private const val PROVISIONAL_THRESHOLD = 0.68
        private const val SEMANTIC_WEIGHT = 0.20
        private const val LINK_THRESHOLD = 0.70
        private const val MAX_REVIEW_GROUPS = 120
        private const val MAX_REVIEW_PER_MODE = 12
        private const val REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-post-calibration-compatible-review.tsv"
        private const val RISK_STRATA_EXPORT_PATH = "/private/tmp/cluster-bge-post-calibration-risk-strata.tsv"
        private const val INDEPENDENT_CANDIDATE_INVENTORY_EXPORT_PATH =
            "/private/tmp/cluster-bge-independent-candidate-inventory.tsv"
        private const val FROZEN_DEVELOPMENT_REVIEW_EXPORT_PATH =
            "/private/tmp/cluster-bge-frozen-development-review.tsv"
        private const val UNTOUCHED_REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-untouched-review.tsv"
        private const val RETIRED_UNTOUCHED_REVIEW_EXPORT_PATH =
            "/private/tmp/cluster-bge-retired-untouched-review.tsv"
        private const val UNTOUCHED_V3_REVIEW_EXPORT_PATH = "/private/tmp/cluster-bge-untouched-v3-review.tsv"
        private const val FROZEN_RESOURCE = "cluster/post-calibration-compatible-freeze-v1.properties"
        private const val GROUND_TRUTH_RESOURCE = "cluster/real-post-calibration-compatible-ground-truth-v1.tsv"
        private const val RISK_GROUND_TRUTH_RESOURCE =
            "cluster/real-post-calibration-unlabeled-risk-ground-truth-v1.tsv"
        private const val RISK_INVENTORY_RESOURCE = "cluster/post-calibration-unlabeled-risk-strata-v1.tsv"
        private const val INDEPENDENT_SAMPLE_RESOURCE = "cluster/independent-post-calibration-blind-sample-v1.tsv"
        private const val INDEPENDENT_AUGMENT_RESOURCE =
            "cluster/independent-post-calibration-blind-augmentation-v1.tsv"
        private const val INDEPENDENT_CHALLENGE_RESOURCE =
            "cluster/independent-post-calibration-blind-challenge-extension-v1.tsv"
        private const val INDEPENDENT_TRUTH_RESOURCE =
            "cluster/real-independent-post-calibration-ground-truth-v1.tsv"
        private const val DECISION_BALANCED_SAMPLE_RESOURCE =
            "cluster/independent-decision-balanced-sample-v1.tsv"
        private const val DECISION_BALANCED_TRUTH_RESOURCE =
            "cluster/real-independent-decision-balanced-ground-truth-v1.tsv"
        private const val DECISION_BALANCED_SAMPLE_V2_RESOURCE =
            "cluster/independent-decision-balanced-sample-v2.tsv"
        private const val DECISION_BALANCED_TRUTH_V2_RESOURCE =
            "cluster/real-independent-decision-balanced-ground-truth-v2.tsv"
        private const val DECISION_BALANCED_SAMPLE_V3_RESOURCE =
            "cluster/independent-decision-balanced-sample-v3.tsv"
        private const val DECISION_BALANCED_TRUTH_V3_RESOURCE =
            "cluster/real-independent-decision-balanced-ground-truth-v3.tsv"
        private val SHADOW10_SAMPLE_REASON_CODES = setOf(
            "SHADOW10_ACCEPT_DECISION_SAMPLE",
            "SHADOW10_PROVISIONAL_DECISION_SAMPLE",
            "SHADOW10_REJECT_DECISION_SAMPLE"
        )
        private val SHADOW11_SAMPLE_REASON_CODES = setOf(
            "SHADOW11_ACCEPT_DECISION_SAMPLE",
            "SHADOW11_PROVISIONAL_DECISION_SAMPLE",
            "SHADOW11_REJECT_DECISION_SAMPLE"
        )
        private val SINGLE_MISSING = setOf(FieldRelation.LEFT_MISSING, FieldRelation.RIGHT_MISSING)
        private val RESIDUAL_PII = Regex(
            "(?i)(?:1[3-9][0-9]{9}|[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])" +
                "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x]|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|" +
                "https?://|www\\.|[\\p{IsHan}]{2,20}(?:小学|中学|学院|幼儿园)|" +
                "(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班)"
        )
        private fun discoveryMode() = System.getenv("CLUSTER_POST_CALIBRATION_COMPATIBLE_FREEZE_DISCOVERY").equals("true", true)
    }
}
