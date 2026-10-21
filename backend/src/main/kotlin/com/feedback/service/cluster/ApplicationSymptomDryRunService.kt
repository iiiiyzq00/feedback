package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.cluster.ClusterProfileFactRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

enum class ApplicationReviewOutcome {
    RESOLVED,
    UNRESOLVED,
    AMBIGUOUS,
    CONTENT_REVIEW_NEEDED,
    CONTENT_INELIGIBLE
}

enum class SymptomBoundaryField {
    APPLICATION,
    FAILURE_MODE,
    TARGET,
    ACTION,
    ERROR_SIGNATURE
}

data class ApplicationSymptomDryRunBatch(
    val inputs: List<ClusterClassificationInput>,
    val nextSourceId: Long,
    val mayHaveMore: Boolean
)

/** 内部分页端口。实现只允许在 dry-run 进程内部暴露数据库游标。 */
fun interface ApplicationSymptomDryRunInputPort {
    fun read(afterSourceId: Long, limit: Int): ApplicationSymptomDryRunBatch
}

/**
 * 只读取当前应用解析结果与影子安全的症状字段。它刻意不依赖
 * Cluster/Link/generation 仓储，因此治理后的复核既无法观察、也无法改动
 * 正在生效的聚类状态。
 */
@Service
class ApplicationSymptomDryRunInputReader(
    private val applications: HistoricalApplicationFactPort,
    private val profileFacts: ClusterProfileFactRepository
) : ApplicationSymptomDryRunInputPort {
    @Transactional(readOnly = true)
    override fun read(afterSourceId: Long, limit: Int): ApplicationSymptomDryRunBatch {
        ensureClusterRequest(afterSourceId >= 0) { "APPLICATION_SYMPTOM_DRY_RUN_CURSOR_INVALID" }
        ensureClusterRequest(limit in 1..MAX_BATCH_SIZE) { "APPLICATION_SYMPTOM_DRY_RUN_BATCH_INVALID" }
        val applicationFacts = applications.read(afterSourceId, limit)
        if (applicationFacts.isEmpty()) return ApplicationSymptomDryRunBatch(emptyList(), afterSourceId, false)
        val ids = applicationFacts.map { it.source.sourceId }
        val facts = profileFacts.loadShadowSafe(ids)
        val inputs = applicationFacts.map { application ->
            val sourceId = application.source.sourceId
            val source = facts.sources[sourceId]
                ?: clusterInvariantViolation("APPLICATION_SYMPTOM_DRY_RUN_SOURCE_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
            val content = facts.contents[sourceId]
                ?: clusterInvariantViolation("APPLICATION_SYMPTOM_DRY_RUN_CONTENT_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
            ClusterClassificationInput(
                sourceId = sourceId,
                applicationKey = application.applicationKey,
                applicationName = application.applicationName,
                title = content.title,
                description = content.description,
                functionModule = content.functionModule,
                issueCategory = content.issueCategory,
                applicationAmbiguous = application.ambiguous,
                contentDisposition = when {
                    content.isDeleted -> ShadowContentDisposition.INELIGIBLE
                    content.maskingStatus == "PASS" -> ShadowContentDisposition.ELIGIBLE
                    content.maskingStatus == "REVIEW_NEEDED" -> ShadowContentDisposition.MASKING_REVIEW_REQUIRED
                    else -> ShadowContentDisposition.INELIGIBLE
                },
                manualConclusion = null,
                sourceChannel = source.sourceChannel,
                applicationEvidence = ApplicationIdentityEvidence.APPROVED_IDENTITY
            )
        }
        return ApplicationSymptomDryRunBatch(inputs, ids.last(), applicationFacts.size == limit)
    }

    companion object { const val MAX_BATCH_SIZE = 100 }
}

data class ApplicationSymptomDryRunReport(
    val algorithmVersion: String,
    val semanticAcceptThreshold: BigDecimal,
    val semanticProvisionalThreshold: BigDecimal,
    val semanticWeight: BigDecimal,
    val semanticEvaluationPerformed: Boolean,
    val processedCount: Int,
    val applicationReviewCounts: Map<ApplicationReviewOutcome, Int>,
    val applicationReviewCountsByChannel: Map<String, Map<ApplicationReviewOutcome, Int>>,
    val symptomStatusCounts: Map<ProblemClassificationStatus, Int>,
    val symptomStatusCountsByChannel: Map<String, Map<ProblemClassificationStatus, Int>>,
    val resolvedSymptomCountsByApplication: Map<String, Map<ProblemClassificationStatus, Int>>,
    val actionableBoundaryKnownCounts: Map<SymptomBoundaryField, Int>,
    val actionableBoundaryKnownCountsByApplication: Map<String, Map<SymptomBoundaryField, Int>>,
    val capabilityDomainCounts: Map<EngineeringCapabilityDomain, Int>,
    val actionableCountsByCapabilityDomain: Map<EngineeringCapabilityDomain, Int>,
    val pendingActionableCountsByCapabilityDomain: Map<EngineeringCapabilityDomain, Int>,
    val actionableCountsByFaultFamily: Map<EngineeringFaultFamily, Int>,
    val actionableCount: Int,
    val nextSourceId: Long,
    val mayHaveMore: Boolean
)

data class ApplicationSymptomDryRunSnapshotReport(
    val algorithmVersion: String,
    val semanticAcceptThreshold: BigDecimal,
    val semanticProvisionalThreshold: BigDecimal,
    val semanticWeight: BigDecimal,
    val semanticEvaluationPerformed: Boolean,
    val processedCount: Int,
    val batchCount: Int,
    val applicationReviewCounts: Map<ApplicationReviewOutcome, Int>,
    val applicationReviewCountsByChannel: Map<String, Map<ApplicationReviewOutcome, Int>>,
    val symptomStatusCounts: Map<ProblemClassificationStatus, Int>,
    val symptomStatusCountsByChannel: Map<String, Map<ProblemClassificationStatus, Int>>,
    val resolvedSymptomCountsByApplication: Map<String, Map<ProblemClassificationStatus, Int>>,
    val actionableBoundaryKnownCounts: Map<SymptomBoundaryField, Int>,
    val actionableBoundaryKnownCountsByApplication: Map<String, Map<SymptomBoundaryField, Int>>,
    val capabilityDomainCounts: Map<EngineeringCapabilityDomain, Int>,
    val actionableCountsByCapabilityDomain: Map<EngineeringCapabilityDomain, Int>,
    val pendingActionableCountsByCapabilityDomain: Map<EngineeringCapabilityDomain, Int>,
    val actionableCountsByFaultFamily: Map<EngineeringFaultFamily, Int>,
    val actionableCount: Int
)

/**
 * 只出聚合值、无状态的治理后复核。不返回也不持久化任何指派、标题、描述、来源 ID 列表、
 * 簇变更、MAIN 链接或代次状态。
 */
@Service
class ApplicationSymptomDryRunService(
    private val input: ApplicationSymptomDryRunInputPort,
    @param:Value("\${app.cluster.application-symptom-dry-run-enabled:false}")
    private val enabled: Boolean = false
) {
    private val policy = SemanticRecallPolicyV3()
    private val extractor = AtomicProblemExtractorV4()

    fun evaluate(afterSourceId: Long, limit: Int): ApplicationSymptomDryRunReport {
        requireEnabled()
        ensureClusterRequest(afterSourceId >= 0) { "APPLICATION_SYMPTOM_DRY_RUN_CURSOR_INVALID" }
        ensureClusterRequest(limit in 1..ApplicationSymptomDryRunInputReader.MAX_BATCH_SIZE) {
            "APPLICATION_SYMPTOM_DRY_RUN_BATCH_INVALID"
        }
        return summarize(input.read(afterSourceId, limit))
    }

    /** 在内存中完成一份有界快照，并从结果里去掉内部数据库游标。 */
    fun evaluateSnapshot(batchSize: Int, maximumRecords: Int): ApplicationSymptomDryRunSnapshotReport {
        requireEnabled()
        ensureClusterRequest(batchSize in 1..ApplicationSymptomDryRunInputReader.MAX_BATCH_SIZE) {
            "APPLICATION_SYMPTOM_DRY_RUN_BATCH_INVALID"
        }
        ensureClusterRequest(maximumRecords in batchSize..MAX_SNAPSHOT_RECORDS) {
            "APPLICATION_SYMPTOM_DRY_RUN_SNAPSHOT_LIMIT_INVALID"
        }
        val pages = mutableListOf<ApplicationSymptomDryRunReport>()
        var cursor = 0L
        var total = 0
        while (true) {
            val limit = minOf(batchSize, maximumRecords - total)
            if (limit == 0) clusterInvariantViolation("APPLICATION_SYMPTOM_DRY_RUN_SNAPSHOT_LIMIT_EXCEEDED")
            val batch = input.read(cursor, limit)
            ensureClusterInvariant(batch.inputs.size <= limit) { "APPLICATION_SYMPTOM_DRY_RUN_PAGE_OVERFLOW" }
            if (batch.inputs.isEmpty()) {
                ensureClusterInvariant(!batch.mayHaveMore) { "APPLICATION_SYMPTOM_DRY_RUN_EMPTY_PAGE_HAS_MORE" }
                break
            }
            ensureClusterInvariant(batch.nextSourceId > cursor) { "APPLICATION_SYMPTOM_DRY_RUN_CURSOR_NOT_ADVANCING" }
            pages += summarize(batch)
            total += batch.inputs.size
            cursor = batch.nextSourceId
            if (!batch.mayHaveMore) break
            if (total >= maximumRecords) {
                clusterInvariantViolation("APPLICATION_SYMPTOM_DRY_RUN_SNAPSHOT_LIMIT_EXCEEDED")
            }
        }
        return ApplicationSymptomDryRunSnapshotReport(
            algorithmVersion = AtomicProblemExtractorV4.VERSION,
            semanticAcceptThreshold = policy.acceptThreshold,
            semanticProvisionalThreshold = policy.provisionalThreshold,
            semanticWeight = policy.semanticWeight,
            semanticEvaluationPerformed = false,
            processedCount = pages.sumOf(ApplicationSymptomDryRunReport::processedCount),
            batchCount = pages.size,
            applicationReviewCounts = mergeCounts(pages.map { it.applicationReviewCounts }),
            applicationReviewCountsByChannel = mergeNestedCounts(pages.map { it.applicationReviewCountsByChannel }),
            symptomStatusCounts = mergeCounts(pages.map { it.symptomStatusCounts }),
            symptomStatusCountsByChannel = mergeNestedCounts(pages.map { it.symptomStatusCountsByChannel }),
            resolvedSymptomCountsByApplication = mergeNestedCounts(
                pages.map { it.resolvedSymptomCountsByApplication }
            ),
            actionableBoundaryKnownCounts = mergeCounts(pages.map { it.actionableBoundaryKnownCounts }),
            actionableBoundaryKnownCountsByApplication = mergeNestedCounts(
                pages.map { it.actionableBoundaryKnownCountsByApplication }
            ),
            capabilityDomainCounts = mergeCounts(pages.map { it.capabilityDomainCounts }),
            actionableCountsByCapabilityDomain = mergeCounts(pages.map { it.actionableCountsByCapabilityDomain }),
            pendingActionableCountsByCapabilityDomain = mergeCounts(
                pages.map { it.pendingActionableCountsByCapabilityDomain }
            ),
            actionableCountsByFaultFamily = mergeCounts(pages.map { it.actionableCountsByFaultFamily }),
            actionableCount = pages.sumOf(ApplicationSymptomDryRunReport::actionableCount)
        )
    }

    private fun summarize(batch: ApplicationSymptomDryRunBatch): ApplicationSymptomDryRunReport {
        val rows = batch.inputs.map { value ->
            val channel = value.sourceChannel ?: "UNKNOWN"
            val application = applicationOutcome(value)
            if (application == ApplicationReviewOutcome.RESOLVED) {
                ensureClusterInvariant(!value.applicationName.isNullOrBlank()) {
                    "APPLICATION_SYMPTOM_RESOLVED_NAME_MISSING"
                }
            }
            AggregateRow(channel, application, value.applicationName, extractor.extract(value))
        }
        val actionable = rows.map(AggregateRow::profile)
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
        return ApplicationSymptomDryRunReport(
            algorithmVersion = AtomicProblemExtractorV4.VERSION,
            semanticAcceptThreshold = policy.acceptThreshold,
            semanticProvisionalThreshold = policy.provisionalThreshold,
            semanticWeight = policy.semanticWeight,
            semanticEvaluationPerformed = false,
            processedCount = rows.size,
            applicationReviewCounts = rows.groupingBy(AggregateRow::application).eachCount().toSortedMap(),
            applicationReviewCountsByChannel = rows.groupBy(AggregateRow::channel).toSortedMap().mapValues { (_, values) ->
                values.groupingBy(AggregateRow::application).eachCount().toSortedMap()
            },
            symptomStatusCounts = rows.groupingBy { it.profile.status }.eachCount().toSortedMap(),
            symptomStatusCountsByChannel = rows.groupBy(AggregateRow::channel).toSortedMap().mapValues { (_, values) ->
                values.groupingBy { it.profile.status }.eachCount().toSortedMap()
            },
            resolvedSymptomCountsByApplication = rows.filter {
                it.application == ApplicationReviewOutcome.RESOLVED
            }.groupBy { it.applicationName ?: clusterInvariantViolation("APPLICATION_SYMPTOM_RESOLVED_NAME_MISSING") }.toSortedMap().mapValues { (_, values) ->
                values.groupingBy { it.profile.status }.eachCount().toSortedMap()
            },
            actionableBoundaryKnownCounts = mapOf(
                SymptomBoundaryField.APPLICATION to actionable.count { !it.applicationKey.isNullOrBlank() },
                SymptomBoundaryField.FAILURE_MODE to actionable.count { !it.failureMode.isNullOrBlank() },
                SymptomBoundaryField.TARGET to actionable.count { !it.target.isNullOrBlank() },
                SymptomBoundaryField.ACTION to actionable.count { !it.action.isNullOrBlank() },
                SymptomBoundaryField.ERROR_SIGNATURE to actionable.count { !it.errorSignature.isNullOrBlank() }
            ),
            actionableBoundaryKnownCountsByApplication = rows.filter {
                it.application == ApplicationReviewOutcome.RESOLVED &&
                    it.profile.status == ProblemClassificationStatus.ACTIONABLE
            }.groupBy { it.applicationName ?: clusterInvariantViolation("APPLICATION_SYMPTOM_RESOLVED_NAME_MISSING") }.toSortedMap().mapValues { (_, values) ->
                mapOf(
                    SymptomBoundaryField.APPLICATION to values.count { !it.profile.applicationKey.isNullOrBlank() },
                    SymptomBoundaryField.FAILURE_MODE to values.count { !it.profile.failureMode.isNullOrBlank() },
                    SymptomBoundaryField.TARGET to values.count { !it.profile.target.isNullOrBlank() },
                    SymptomBoundaryField.ACTION to values.count { !it.profile.action.isNullOrBlank() },
                    SymptomBoundaryField.ERROR_SIGNATURE to values.count { !it.profile.errorSignature.isNullOrBlank() }
                )
            },
            capabilityDomainCounts = rows.mapNotNull { it.profile.capabilityDomain }
                .groupingBy { it }.eachCount().toSortedMap(),
            actionableCountsByCapabilityDomain = actionable.mapNotNull { it.capabilityDomain }
                .groupingBy { it }.eachCount().toSortedMap(),
            pendingActionableCountsByCapabilityDomain = rows.filter {
                it.application in setOf(ApplicationReviewOutcome.UNRESOLVED, ApplicationReviewOutcome.AMBIGUOUS) &&
                    it.profile.status == ProblemClassificationStatus.ACTIONABLE
            }.mapNotNull { it.profile.capabilityDomain }.groupingBy { it }.eachCount().toSortedMap(),
            actionableCountsByFaultFamily = actionable.mapNotNull { it.faultFamily }
                .groupingBy { it }.eachCount().toSortedMap(),
            actionableCount = actionable.size,
            nextSourceId = batch.nextSourceId,
            mayHaveMore = batch.mayHaveMore
        )
    }

    private fun requireEnabled() {
        if (!enabled) throw ServiceException(
            ErrorCode.CLUSTER_SHADOW_EVALUATION_DISABLED,
            "APPLICATION_SYMPTOM_DRY_RUN_DISABLED"
        )
    }

    private fun <K : Comparable<K>> mergeCounts(values: Collection<Map<K, Int>>): Map<K, Int> {
        val merged = sortedMapOf<K, Int>()
        values.forEach { counts ->
            counts.forEach { (key, count) -> merged[key] = (merged[key] ?: 0) + count }
        }
        return merged
    }

    private fun <K : Comparable<K>> mergeNestedCounts(
        values: Collection<Map<String, Map<K, Int>>>
    ): Map<String, Map<K, Int>> = values.flatMap(Map<String, Map<K, Int>>::entries)
        .groupBy({ it.key }, { it.value }).toSortedMap().mapValues { (_, counts) -> mergeCounts(counts) }

    private fun applicationOutcome(input: ClusterClassificationInput): ApplicationReviewOutcome = when {
        input.contentDisposition == ShadowContentDisposition.MASKING_REVIEW_REQUIRED ->
            ApplicationReviewOutcome.CONTENT_REVIEW_NEEDED
        input.contentDisposition == ShadowContentDisposition.INELIGIBLE -> ApplicationReviewOutcome.CONTENT_INELIGIBLE
        input.applicationAmbiguous -> ApplicationReviewOutcome.AMBIGUOUS
        input.applicationKey.isNullOrBlank() -> ApplicationReviewOutcome.UNRESOLVED
        else -> ApplicationReviewOutcome.RESOLVED
    }

    private data class AggregateRow(
        val channel: String,
        val application: ApplicationReviewOutcome,
        val applicationName: String?,
        val profile: AtomicProblemProfile
    )

    companion object { const val MAX_SNAPSHOT_RECORDS = 50_000 }
}
