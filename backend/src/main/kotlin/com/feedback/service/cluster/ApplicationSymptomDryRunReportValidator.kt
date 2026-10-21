package com.feedback.service.cluster

import org.springframework.stereotype.Component

/** 在允许 CLI 输出之前，校验一个完整的聚合结果。 */
@Component
class ApplicationSymptomDryRunReportValidator {
    fun validate(
        report: ApplicationSymptomDryRunSnapshotReport,
        expectedRecordCount: Int,
        expectedReviewNeededCount: Int? = null
    ): ApplicationSymptomDryRunSnapshotReport {
        ensureClusterRequest(expectedRecordCount > 0) { "APPLICATION_SYMPTOM_EXPECTED_COUNT_INVALID" }
        expectedReviewNeededCount?.let {
            ensureClusterRequest(it >= 0) { "APPLICATION_SYMPTOM_EXPECTED_REVIEW_COUNT_INVALID" }
        }
        ensureClusterInvariant(report.algorithmVersion == AtomicProblemExtractorV4.VERSION) {
            "APPLICATION_SYMPTOM_ALGORITHM_VERSION_DRIFT"
        }
        ensureClusterInvariant(report.semanticAcceptThreshold.toPlainString() == "0.820000" &&
            report.semanticProvisionalThreshold.toPlainString() == "0.680000" &&
            report.semanticWeight.toPlainString() == "0.200000") {
            "APPLICATION_SYMPTOM_POLICY_DRIFT"
        }
        ensureClusterInvariant(!report.semanticEvaluationPerformed) {
            "APPLICATION_SYMPTOM_STATELESS_SEMANTIC_FORBIDDEN"
        }
        ensureClusterInvariant(report.processedCount == expectedRecordCount) {
            "APPLICATION_SYMPTOM_DENOMINATOR_DRIFT"
        }
        ensureClusterInvariant(report.applicationReviewCounts.values.sum() == report.processedCount) {
            "APPLICATION_SYMPTOM_APPLICATION_TOTAL_MISMATCH"
        }
        ensureClusterInvariant(report.symptomStatusCounts.values.sum() == report.processedCount) {
            "APPLICATION_SYMPTOM_STATUS_TOTAL_MISMATCH"
        }
        ensureClusterInvariant(report.applicationReviewCountsByChannel.values.sumOf { it.values.sum() } ==
            report.processedCount) { "APPLICATION_SYMPTOM_APPLICATION_CHANNEL_TOTAL_MISMATCH" }
        ensureClusterInvariant(report.symptomStatusCountsByChannel.values.sumOf { it.values.sum() } ==
            report.processedCount) { "APPLICATION_SYMPTOM_STATUS_CHANNEL_TOTAL_MISMATCH" }
        ensureClusterInvariant(report.applicationReviewCountsByChannel.keys == report.symptomStatusCountsByChannel.keys) {
            "APPLICATION_SYMPTOM_CHANNEL_SCOPE_MISMATCH"
        }
        report.applicationReviewCountsByChannel.keys.forEach { channel ->
            ensureClusterInvariant(
                report.applicationReviewCountsByChannel.getValue(channel).values.sum() ==
                    report.symptomStatusCountsByChannel.getValue(channel).values.sum()
            ) { "APPLICATION_SYMPTOM_CHANNEL_DENOMINATOR_MISMATCH" }
        }
        val actionableFromStatus = report.symptomStatusCounts[ProblemClassificationStatus.ACTIONABLE] ?: 0
        val resolved = report.applicationReviewCounts[ApplicationReviewOutcome.RESOLVED] ?: 0
        ensureClusterInvariant(report.actionableCount == actionableFromStatus) {
            "APPLICATION_SYMPTOM_ACTIONABLE_COUNT_MISMATCH"
        }
        ensureClusterInvariant(
            report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION].orZero() <= report.actionableCount &&
                report.actionableBoundaryKnownCounts[SymptomBoundaryField.FAILURE_MODE] == report.actionableCount &&
                report.actionableBoundaryKnownCounts.values.all { it in 0..report.actionableCount }
        ) { "APPLICATION_SYMPTOM_BOUNDARY_COVERAGE_INVALID" }
        ensureClusterInvariant(
            report.resolvedSymptomCountsByApplication.values.sumOf { it.values.sum() } == resolved &&
                report.resolvedSymptomCountsByApplication.keys.none(String::isBlank)
        ) { "APPLICATION_SYMPTOM_APPLICATION_AGGREGATE_INVALID" }
        ensureClusterInvariant(
            report.resolvedSymptomCountsByApplication.values.sumOf {
                it[ProblemClassificationStatus.ACTIONABLE] ?: 0
            } == report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION].orZero()
        ) { "APPLICATION_SYMPTOM_APPLICATION_ACTIONABLE_MISMATCH" }
        ensureClusterInvariant(
            report.actionableBoundaryKnownCountsByApplication.values.sumOf {
                it[SymptomBoundaryField.APPLICATION] ?: 0
            } == report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION].orZero() &&
                report.actionableBoundaryKnownCountsByApplication.values.sumOf {
                    it[SymptomBoundaryField.FAILURE_MODE] ?: 0
                } == report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION].orZero()
        ) { "APPLICATION_SYMPTOM_APPLICATION_BOUNDARY_MISMATCH" }
        report.actionableBoundaryKnownCountsByApplication.forEach { (application, counts) ->
            val applicationActionable = report.resolvedSymptomCountsByApplication[application]
                ?.get(ProblemClassificationStatus.ACTIONABLE) ?: 0
            ensureClusterInvariant(counts.values.all { it in 0..applicationActionable }) {
                "APPLICATION_SYMPTOM_APPLICATION_BOUNDARY_INVALID"
            }
        }
        ensureClusterInvariant(report.capabilityDomainCounts.values.sum() <= report.processedCount &&
            report.actionableCountsByCapabilityDomain.values.sum() == report.actionableCount &&
            report.actionableCountsByFaultFamily.values.sum() == report.actionableCount &&
            report.pendingActionableCountsByCapabilityDomain.values.sum() ==
                report.actionableCount - report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION].orZero()
        ) { "APPLICATION_SYMPTOM_ENGINEERING_TAXONOMY_TOTAL_MISMATCH" }
        expectedReviewNeededCount?.let { expected ->
            ensureClusterInvariant(
                report.applicationReviewCounts[ApplicationReviewOutcome.CONTENT_REVIEW_NEEDED].orZero() == expected &&
                    report.symptomStatusCounts[ProblemClassificationStatus.CONTENT_REVIEW_NEEDED].orZero() == expected
            ) { "APPLICATION_SYMPTOM_REVIEW_COUNT_DRIFT" }
        }
        return report
    }

    private fun Int?.orZero() = this ?: 0
}
