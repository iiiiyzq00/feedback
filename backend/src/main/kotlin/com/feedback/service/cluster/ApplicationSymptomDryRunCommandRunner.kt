package com.feedback.service.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.exception.AbstractException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** 一次性、只出聚合值的命令。它只输出一个经过校验的 JSON 对象，不做任何写入。 */
@Component
@ConditionalOnProperty(
    prefix = "app.cluster.application-symptom-dry-run",
    name = ["command-enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class ApplicationSymptomDryRunCommandRunner(
    private val service: ApplicationSymptomDryRunService,
    private val validator: ApplicationSymptomDryRunReportValidator,
    private val json: ObjectMapper,
    @param:Value("\${app.cluster.application-symptom-dry-run.confirmation:}")
    private val confirmation: String,
    @param:Value("\${app.cluster.application-symptom-dry-run.batch-size:100}")
    private val batchSize: Int,
    @param:Value("\${app.cluster.application-symptom-dry-run.maximum-records:50000}")
    private val maximumRecords: Int,
    @param:Value("\${app.cluster.application-symptom-dry-run.expected-record-count:0}")
    private val expectedRecordCount: Int,
    @param:Value("\${app.cluster.application-symptom-dry-run.expected-review-needed-count:-1}")
    private val expectedReviewNeededCount: Int
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        try {
            ensureClusterRequest(confirmation == CONFIRMATION) { "APPLICATION_SYMPTOM_READ_ONLY_CONFIRMATION_REQUIRED" }
            val expectedReview = expectedReviewNeededCount.takeIf { it >= 0 }
            val report = service.evaluateSnapshot(batchSize, maximumRecords)
            validator.validate(report, expectedRecordCount, expectedReview)
            System.out.println(json.writeValueAsString(report))
        } catch (error: AbstractException) {
            System.err.println("APPLICATION_SYMPTOM_DRY_RUN_FAILED:${error.reasonCode ?: "UNCLASSIFIED"}")
            throw error
        }
    }

    companion object { const val CONFIRMATION = "READ_ONLY_AGGREGATE_SNAPSHOT" }
}
