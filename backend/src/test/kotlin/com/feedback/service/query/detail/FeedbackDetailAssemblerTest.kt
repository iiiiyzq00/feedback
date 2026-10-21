package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import com.feedback.model.auth.DataFieldPolicy

class FeedbackDetailAssemblerTest {
    @Test
    fun `five channel facts remain a closed named union`() {
        val values: List<FeedbackChannelFacts> = listOf(
            mockk<SdkFeedbackChannelFacts>(),
            mockk<JiraFeedbackChannelFacts>(),
            mockk<QualityFeedbackChannelFacts>(),
            mockk<RedblackFeedbackChannelFacts>(),
            mockk<StationFeedbackChannelFacts>()
        )

        assertEquals(
            listOf(
                SdkFeedbackChannelFacts::class,
                JiraFeedbackChannelFacts::class,
                QualityFeedbackChannelFacts::class,
                RedblackFeedbackChannelFacts::class,
                StationFeedbackChannelFacts::class
            ),
            values.map { it::class }
        )
    }

    @Test
    fun `assembler alone maps application facts into channel attachment and navigation responses`() {
        val policy = DataFieldPolicy(true, emptySet(), emptySet())
        val snapshot = FeedbackDetailSnapshot(
            source = FeedbackSourceSnapshot(
                id = 7, sourceChannel = "JIRA", sourceRecordId = "FP-7", appVersion = null,
                provinceCode = null, cityCode = null, districtCode = null, faultOccurredAt = null,
                faultTimePrecision = null, faultTimeSource = null, faultTimeResolution = null,
                ingestTime = null, sourceUpdateTime = null, importBatchId = null
            ),
            content = FeedbackContentSnapshot(
                title = "标题", description = "描述", functionModule = "COURSE", usageStage = null,
                problemType = null, issueCategory = null, severityLevel = null, userSentiment = null,
                reproducibility = null, processingStatus = "OPEN", maskingStatus = "PASS",
                mappingRuleVersion = null, normalizationVersion = null, dataQualityScore = null,
                qualityRuleVersion = null, updatedAt = null, deleted = false, createdAt = null
            ),
            ai = null,
            importBatch = null,
            workflow = null,
            assignment = null,
            owner = null,
            channelFacts = jiraFacts(),
            externalLinkFacts = JiraExternalLinkFacts("FP-7"),
            diagnostics = FeedbackDiagnosticSnapshot(emptySet(), false),
            fieldHistory = emptyList(),
            attachments = listOf(
                FeedbackAttachmentSummaryFact(
                    id = 9, attachmentType = "SCREENSHOT", mimeType = "image/png", fileSizeBytes = 10,
                    width = 2, height = 3, durationSeconds = null, uploadStatus = "VERIFIED",
                    securityStatus = "VERIFIED", integrityStatus = "VERIFIED", decryptionStatus = "DECRYPTED",
                    malwareScanStatus = "CLEAN", contentReviewStatus = "PASS", sourceKind = "JIRA",
                    retentionUntil = null, createdAt = null, available = true
                )
            ),
            navigation = FeedbackNavigationSnapshot(previousId = 6, nextId = 8),
            cluster = null
        )

        val response = FeedbackDetailAssembler().detail(snapshot, externalLinkAvailable = true, policy = policy)

        assertEquals("FP-7", (response.channelMeta as JiraFeedbackChannelMetaResponse).jiraKey)
        assertEquals(9, response.attachments.single().id)
        assertSame(policy, response.attachments.single().fieldPolicy)
        assertEquals(6, response.navigation.previousId)
        assertEquals(8, response.navigation.nextId)
        assertSame(policy, response.navigation.fieldPolicy)
    }

    private fun jiraFacts() = JiraFeedbackChannelFacts(
        jiraKey = "FP-7", priorityRaw = null, jiraStatusRaw = "处理中", projectKey = null,
        projectName = null, resolution = null, assignee = null, jiraCreatedAt = null,
        jiraUpdatedAt = null, resolvedAt = null, affectedVersion = null, fixVersion = null,
        moduleRaw = null, dueDate = null, label1 = null, label2 = null, label3 = null,
        commentSummary = null
    )
}
