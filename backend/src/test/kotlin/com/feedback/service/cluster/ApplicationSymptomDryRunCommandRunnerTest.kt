package com.feedback.service.cluster

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.exception.ClientException
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ApplicationSymptomDryRunCommandRunnerTest {
    @Test
    fun `确认口令缺失时不会读取快照`() {
        var reads = 0
        val service = ApplicationSymptomDryRunService(ApplicationSymptomDryRunInputPort { _, _ ->
            reads++
            ApplicationSymptomDryRunBatch(emptyList(), 0, false)
        }, enabled = true)
        val runner = ApplicationSymptomDryRunCommandRunner(
            service,
            ApplicationSymptomDryRunReportValidator(),
            jacksonObjectMapper(),
            confirmation = "",
            batchSize = 100,
            maximumRecords = 100,
            expectedRecordCount = 1,
            expectedReviewNeededCount = -1
        )

        assertThrows(ClientException::class.java) { runner.run(mockk<ApplicationArguments>()) }
        assertEquals(0, reads)
    }

    @Test
    fun `执行器只输出通过不变量校验的聚合 JSON`() {
        val service = ApplicationSymptomDryRunService(ApplicationSymptomDryRunInputPort { cursor, _ ->
            if (cursor == 0L) ApplicationSymptomDryRunBatch(
                listOf(ClusterClassificationInput(
                    sourceId = 1,
                    applicationKey = "course",
                    applicationName = "知识课程",
                    title = null,
                    description = "播放视频时卡顿",
                    sourceChannel = "JIRA"
                )),
                nextSourceId = 1,
                mayHaveMore = false
            ) else ApplicationSymptomDryRunBatch(emptyList(), cursor, false)
        }, enabled = true)
        val output = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(output, true, Charsets.UTF_8))
            ApplicationSymptomDryRunCommandRunner(
                service,
                ApplicationSymptomDryRunReportValidator(),
                jacksonObjectMapper(),
                confirmation = ApplicationSymptomDryRunCommandRunner.CONFIRMATION,
                batchSize = 100,
                maximumRecords = 100,
                expectedRecordCount = 1,
                expectedReviewNeededCount = 0
            ).run(mockk<ApplicationArguments>())
        } finally {
            System.setOut(previous)
        }

        val emitted = output.toString(Charsets.UTF_8).trim()
        val node = jacksonObjectMapper().readTree(emitted)
        assertEquals(1, node.path("processedCount").asInt())
        assertEquals(1, node.path("actionableCount").asInt())
        listOf("sourceId", "cursor", "title", "description", "assignments").forEach { prohibited ->
            assertFalse(emitted.contains(prohibited, ignoreCase = true), prohibited)
        }
    }

    @Test
    fun `分母漂移使整份快照拒绝输出`() {
        val report = snapshotReport(processed = 1)

        val error = assertThrows(Exception::class.java) {
            ApplicationSymptomDryRunReportValidator().validate(report, expectedRecordCount = 2)
        }

        assertEquals("APPLICATION_SYMPTOM_DENOMINATOR_DRIFT", (error as com.feedback.common.exception.ServiceException).reasonCode)
    }

    @Test
    fun `待确认应用的可执行症状不冒充已确认应用边界`() {
        val report = snapshotReport(processed = 2).copy(
            applicationReviewCounts = mapOf(
                ApplicationReviewOutcome.RESOLVED to 1,
                ApplicationReviewOutcome.UNRESOLVED to 1
            ),
            applicationReviewCountsByChannel = mapOf(
                "JIRA" to mapOf(ApplicationReviewOutcome.RESOLVED to 1),
                "REDBLACK" to mapOf(ApplicationReviewOutcome.UNRESOLVED to 1)
            ),
            symptomStatusCountsByChannel = mapOf(
                "JIRA" to mapOf(ProblemClassificationStatus.ACTIONABLE to 1),
                "REDBLACK" to mapOf(ProblemClassificationStatus.ACTIONABLE to 1)
            ),
            resolvedSymptomCountsByApplication = mapOf(
                "知识课程" to mapOf(ProblemClassificationStatus.ACTIONABLE to 1)
            ),
            actionableBoundaryKnownCounts = mapOf(
                SymptomBoundaryField.APPLICATION to 1,
                SymptomBoundaryField.FAILURE_MODE to 2,
                SymptomBoundaryField.TARGET to 2,
                SymptomBoundaryField.ACTION to 2,
                SymptomBoundaryField.ERROR_SIGNATURE to 0
            ),
            actionableBoundaryKnownCountsByApplication = mapOf(
                "知识课程" to mapOf(
                    SymptomBoundaryField.APPLICATION to 1,
                    SymptomBoundaryField.FAILURE_MODE to 1,
                    SymptomBoundaryField.TARGET to 1,
                    SymptomBoundaryField.ACTION to 1,
                    SymptomBoundaryField.ERROR_SIGNATURE to 0
                )
            ),
            capabilityDomainCounts = mapOf(EngineeringCapabilityDomain.MEDIA to 2),
            actionableCountsByCapabilityDomain = mapOf(EngineeringCapabilityDomain.MEDIA to 2),
            pendingActionableCountsByCapabilityDomain = mapOf(EngineeringCapabilityDomain.MEDIA to 1),
            actionableCountsByFaultFamily = mapOf(EngineeringFaultFamily.MEDIA_IO to 2)
        )

        val validated = ApplicationSymptomDryRunReportValidator().validate(report, expectedRecordCount = 2)

        assertEquals(1, validated.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION])
        assertEquals(1, validated.pendingActionableCountsByCapabilityDomain[EngineeringCapabilityDomain.MEDIA])
    }

    @Test
    fun `执行器失败只输出稳定原因码且不输出聚合内容`() {
        val service = ApplicationSymptomDryRunService(ApplicationSymptomDryRunInputPort { _, _ ->
            error("confirmation must fail before reading")
        }, enabled = true)
        val stderr = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            assertThrows(ClientException::class.java) {
                ApplicationSymptomDryRunCommandRunner(
                    service,
                    ApplicationSymptomDryRunReportValidator(),
                    jacksonObjectMapper(),
                    confirmation = "",
                    batchSize = 100,
                    maximumRecords = 100,
                    expectedRecordCount = 1,
                    expectedReviewNeededCount = 0
                ).run(mockk<ApplicationArguments>())
            }
        } finally {
            System.setErr(previous)
        }

        assertEquals(
            "APPLICATION_SYMPTOM_DRY_RUN_FAILED:APPLICATION_SYMPTOM_READ_ONLY_CONFIRMATION_REQUIRED",
            stderr.toString(Charsets.UTF_8).trim()
        )
    }

    private fun snapshotReport(processed: Int) = ApplicationSymptomDryRunSnapshotReport(
        algorithmVersion = AtomicProblemExtractorV4.VERSION,
        semanticAcceptThreshold = java.math.BigDecimal("0.820000"),
        semanticProvisionalThreshold = java.math.BigDecimal("0.680000"),
        semanticWeight = java.math.BigDecimal("0.200000"),
        semanticEvaluationPerformed = false,
        processedCount = processed,
        batchCount = 1,
        applicationReviewCounts = mapOf(ApplicationReviewOutcome.RESOLVED to processed),
        applicationReviewCountsByChannel = mapOf("JIRA" to mapOf(ApplicationReviewOutcome.RESOLVED to processed)),
        symptomStatusCounts = mapOf(ProblemClassificationStatus.ACTIONABLE to processed),
        symptomStatusCountsByChannel = mapOf("JIRA" to mapOf(ProblemClassificationStatus.ACTIONABLE to processed)),
        resolvedSymptomCountsByApplication = mapOf(
            "知识课程" to mapOf(ProblemClassificationStatus.ACTIONABLE to processed)
        ),
        actionableBoundaryKnownCounts = mapOf(
            SymptomBoundaryField.APPLICATION to processed,
            SymptomBoundaryField.FAILURE_MODE to processed,
            SymptomBoundaryField.TARGET to processed,
            SymptomBoundaryField.ACTION to processed,
            SymptomBoundaryField.ERROR_SIGNATURE to 0
        ),
        actionableBoundaryKnownCountsByApplication = mapOf(
            "知识课程" to mapOf(
                SymptomBoundaryField.APPLICATION to processed,
                SymptomBoundaryField.FAILURE_MODE to processed,
                SymptomBoundaryField.TARGET to processed,
                SymptomBoundaryField.ACTION to processed,
                SymptomBoundaryField.ERROR_SIGNATURE to 0
            )
        ),
        capabilityDomainCounts = mapOf(EngineeringCapabilityDomain.MEDIA to processed),
        actionableCountsByCapabilityDomain = mapOf(EngineeringCapabilityDomain.MEDIA to processed),
        pendingActionableCountsByCapabilityDomain = emptyMap(),
        actionableCountsByFaultFamily = mapOf(EngineeringFaultFamily.MEDIA_IO to processed),
        actionableCount = processed
    )
}
