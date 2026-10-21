package com.feedback.service.cluster

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.cluster.ClusterShadowInputFacts
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationSymptomDryRunServiceTest {
    @Test
    fun `只读装配器不依赖现有 cluster link 或 generation`() {
        val applications = HistoricalApplicationFactPort { after, limit ->
            assertEquals(0, after)
            assertEquals(2, limit)
            listOf(
                application(1, ApplicationSourceChannel.JIRA, "course", "知识课程"),
                application(2, ApplicationSourceChannel.REDBLACK, null, null)
            )
        }
        val profiles = mockk<ClusterProfileFactRepository>()
        every { profiles.loadShadowSafe(listOf(1L, 2L)) } returns ClusterShadowInputFacts(
            sources = mapOf(
                1L to FeedbackSource(id = 1, sourceChannel = "JIRA"),
                2L to FeedbackSource(id = 2, sourceChannel = "REDBLACK")
            ),
            contents = mapOf(
                1L to FeedbackContent(sourceId = 1, description = "播放视频时卡顿", maskingStatus = "PASS"),
                2L to FeedbackContent(sourceId = 2, maskingStatus = "REVIEW_NEEDED")
            )
        )

        val batch = ApplicationSymptomDryRunInputReader(applications, profiles).read(0, 2)

        assertEquals(2, batch.inputs.size)
        assertEquals(ShadowContentDisposition.ELIGIBLE, batch.inputs[0].contentDisposition)
        assertEquals(ShadowContentDisposition.MASKING_REVIEW_REQUIRED, batch.inputs[1].contentDisposition)
        assertTrue(batch.inputs.all { it.manualConclusion == null })
        assertEquals(listOf("course", null), batch.inputs.map { it.applicationKey })
        verify(exactly = 1) { profiles.loadShadowSafe(listOf(1L, 2L)) }
    }

    @Test
    fun `建档后复审与无状态症状 dry run 只返回聚合`() {
        val batch = ApplicationSymptomDryRunBatch(
            inputs = listOf(
                input(1, "course", "知识课程", "播放视频时卡顿", "JIRA"),
                input(2, "reader", "绘本伴读", "播放视频时卡顿", "REDBLACK"),
                input(3, null, null, "播放视频时卡顿", "JIRA"),
                input(4, null, null, "播放视频时卡顿", "REDBLACK", ambiguous = true),
                input(5, null, null, null, "REDBLACK", reviewNeeded = true)
            ),
            nextSourceId = 5,
            mayHaveMore = true
        )
        val service = ApplicationSymptomDryRunService(
            ApplicationSymptomDryRunInputPort { _, _ -> batch }, enabled = true
        )

        val report = service.evaluate(0, 5)

        assertEquals(AtomicProblemExtractorV4.VERSION, report.algorithmVersion)
        assertEquals("0.820000", report.semanticAcceptThreshold.toPlainString())
        assertEquals("0.680000", report.semanticProvisionalThreshold.toPlainString())
        assertEquals("0.200000", report.semanticWeight.toPlainString())
        assertFalse(report.semanticEvaluationPerformed)
        assertEquals(5, report.processedCount)
        assertEquals(2, report.applicationReviewCounts[ApplicationReviewOutcome.RESOLVED])
        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.UNRESOLVED])
        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.AMBIGUOUS])
        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.CONTENT_REVIEW_NEEDED])
        assertEquals(4, report.actionableCount)
        assertEquals(1, report.resolvedSymptomCountsByApplication.getValue("知识课程")
            .getValue(ProblemClassificationStatus.ACTIONABLE))
        assertEquals(1, report.resolvedSymptomCountsByApplication.getValue("绘本伴读")
            .getValue(ProblemClassificationStatus.ACTIONABLE))
        assertEquals(2, report.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION])
        assertEquals(4, report.actionableBoundaryKnownCounts[SymptomBoundaryField.FAILURE_MODE])
        assertEquals(4, report.actionableBoundaryKnownCounts[SymptomBoundaryField.TARGET])
        assertEquals(4, report.actionableBoundaryKnownCounts[SymptomBoundaryField.ACTION])
        assertEquals(0, report.actionableBoundaryKnownCounts[SymptomBoundaryField.ERROR_SIGNATURE])
        assertEquals(1, report.actionableBoundaryKnownCountsByApplication.getValue("知识课程")
            .getValue(SymptomBoundaryField.TARGET))
        assertEquals(2, report.pendingActionableCountsByCapabilityDomain[EngineeringCapabilityDomain.MEDIA])
        assertEquals(4, report.actionableCountsByCapabilityDomain[EngineeringCapabilityDomain.MEDIA])
        assertEquals(4, report.actionableCountsByFaultFamily[EngineeringFaultFamily.STABILITY])
        assertEquals(1, report.symptomStatusCounts[ProblemClassificationStatus.CONTENT_REVIEW_NEEDED])
        assertFalse(ApplicationSymptomDryRunReport::class.java.declaredFields.any { it.name == "assignments" })
    }

    @Test
    fun `同一只读快照重复 dry run 聚合不漂移`() {
        var reads = 0
        val batch = ApplicationSymptomDryRunBatch(
            listOf(
                input(11, "course", "知识课程", "播放视频时卡顿", "JIRA"),
                input(12, "course", "知识课程", "播放视频时卡顿", "REDBLACK")
            ),
            12,
            false
        )
        val service = ApplicationSymptomDryRunService(ApplicationSymptomDryRunInputPort { _, _ ->
            reads++
            batch
        }, enabled = true)

        val first = service.evaluate(0, 2)
        val second = service.evaluate(0, 2)

        assertEquals(first, second)
        assertEquals(2, reads)
        assertEquals(2, first.actionableCount)
        assertEquals(2, first.actionableBoundaryKnownCounts[SymptomBoundaryField.APPLICATION])
    }

    @Test
    fun `已删除内容优先失败关闭且不得因 application 已解析进入症状处理`() {
        val batch = ApplicationSymptomDryRunBatch(
            inputs = listOf(
                ClusterClassificationInput(
                    sourceId = 21,
                    applicationKey = "course",
                    applicationName = "知识课程",
                    title = null,
                    description = null,
                    contentDisposition = ShadowContentDisposition.INELIGIBLE,
                    sourceChannel = "REDBLACK",
                    applicationEvidence = ApplicationIdentityEvidence.APPROVED_IDENTITY
                )
            ),
            nextSourceId = 21,
            mayHaveMore = false
        )
        val report = ApplicationSymptomDryRunService(
            ApplicationSymptomDryRunInputPort { _, _ -> batch }, enabled = true
        ).evaluate(0, 1)

        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.CONTENT_INELIGIBLE])
        assertEquals(1, report.symptomStatusCounts[ProblemClassificationStatus.CONTENT_INELIGIBLE])
        assertEquals(0, report.actionableCount)
        assertTrue(report.actionableBoundaryKnownCounts.values.all { it == 0 })
    }

    @Test
    fun `只读装配器把软删除内容标为不可处理且不携带正文`() {
        val applications = HistoricalApplicationFactPort { _, _ ->
            listOf(application(31, ApplicationSourceChannel.REDBLACK, "course", "知识课程"))
        }
        val profiles = mockk<ClusterProfileFactRepository>()
        every { profiles.loadShadowSafe(listOf(31L)) } returns ClusterShadowInputFacts(
            sources = mapOf(31L to FeedbackSource(id = 31, sourceChannel = "REDBLACK")),
            contents = mapOf(
                31L to FeedbackContent(
                    sourceId = 31,
                    title = null,
                    description = null,
                    maskingStatus = "PASS",
                    isDeleted = true
                )
            )
        )

        val value = ApplicationSymptomDryRunInputReader(applications, profiles).read(0, 1).inputs.single()

        assertEquals(ShadowContentDisposition.INELIGIBLE, value.contentDisposition)
        assertEquals(null, value.title)
        assertEquals(null, value.description)
    }

    @Test
    fun `完整快照跨批汇总且不暴露内部游标`() {
        val batches = mapOf(
            0L to ApplicationSymptomDryRunBatch(
                listOf(input(1, "course", "知识课程", "播放视频时卡顿", "JIRA")), 1, true
            ),
            1L to ApplicationSymptomDryRunBatch(
                listOf(input(2, null, null, "播放视频时卡顿", "REDBLACK")), 2, false
            )
        )
        val service = ApplicationSymptomDryRunService(ApplicationSymptomDryRunInputPort { cursor, _ ->
            batches.getValue(cursor)
        }, enabled = true)

        val report = service.evaluateSnapshot(batchSize = 1, maximumRecords = 3)

        assertEquals(2, report.processedCount)
        assertEquals(2, report.batchCount)
        assertEquals(2, report.actionableCount)
        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.RESOLVED])
        assertEquals(1, report.applicationReviewCounts[ApplicationReviewOutcome.UNRESOLVED])
        assertEquals(1, report.resolvedSymptomCountsByApplication.getValue("知识课程")
            .getValue(ProblemClassificationStatus.ACTIONABLE))
        assertEquals(1, report.pendingActionableCountsByCapabilityDomain[EngineeringCapabilityDomain.MEDIA])
        assertFalse(ApplicationSymptomDryRunSnapshotReport::class.java.declaredFields.any {
            it.name.contains("source", ignoreCase = true) || it.name.contains("cursor", ignoreCase = true)
        })
    }

    @Test
    fun `关闭或游标越界时在读取前失败关闭`() {
        var reads = 0
        val port = ApplicationSymptomDryRunInputPort { _, _ ->
            reads++
            ApplicationSymptomDryRunBatch(emptyList(), 0, false)
        }
        assertThrows(ServiceException::class.java) {
            ApplicationSymptomDryRunService(port).evaluate(0, 1)
        }
        val enabled = ApplicationSymptomDryRunService(port, enabled = true)
        assertThrows(ClientException::class.java) { enabled.evaluate(-1, 1) }
        assertThrows(ClientException::class.java) { enabled.evaluate(0, 0) }
        assertThrows(ClientException::class.java) { enabled.evaluate(0, 101) }
        assertEquals(0, reads)
    }

    private fun application(
        id: Long,
        channel: ApplicationSourceChannel,
        key: String?,
        name: String?
    ) = HistoricalApplicationFact(
        ApplicationIdentitySourceInput(id, channel, emptyList(), null), key, name, false
    )

    private fun input(
        id: Long,
        key: String?,
        name: String?,
        description: String?,
        channel: String,
        ambiguous: Boolean = false,
        reviewNeeded: Boolean = false
    ) = ClusterClassificationInput(
        sourceId = id,
        applicationKey = key,
        applicationName = name,
        title = null,
        description = description,
        applicationAmbiguous = ambiguous,
        contentDisposition = if (reviewNeeded) ShadowContentDisposition.MASKING_REVIEW_REQUIRED
            else ShadowContentDisposition.ELIGIBLE,
        sourceChannel = channel
    )
}
