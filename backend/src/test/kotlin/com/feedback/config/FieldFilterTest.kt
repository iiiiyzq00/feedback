package com.feedback.config

import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.FieldScopedResponse
import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import com.feedback.service.query.detail.FeedbackClusterSummaryResponse
import com.feedback.service.query.detail.FeedbackDetailResponse
import com.feedback.service.query.detail.FeedbackDiagnosticAvailabilityResponse
import com.feedback.service.query.detail.FeedbackNavigationResponse
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldFilterTest {
    private val mapper = JacksonConfig().objectMapper()

    @Test
    fun `field policy omits denied top-level and nested properties without serializing policy`() {
        val policy = DataFieldPolicy(
            unrestricted = false,
            allowed = setOf("TITLE", "ANALYSIS", "SUMMARY"),
            denied = setOf("DESCRIPTION", "REASONING")
        )
        val json = mapper.writeValueAsString(
            ScopedDetail(
                id = 7,
                title = "安全标题",
                description = "禁止正文",
                analysis = ScopedAnalysis("安全摘要", "禁止推理", policy),
                fieldPolicy = policy
            )
        )

        assertTrue(json.contains("\"id\":7"))
        assertTrue(json.contains("\"title\":\"安全标题\""))
        assertTrue(json.contains("\"summary\":\"安全摘要\""))
        assertFalse(json.contains("description"))
        assertFalse(json.contains("reasoning"))
        assertFalse(json.contains("fieldPolicy"))
    }

    @Test
    fun `real detail nested DTOs apply the same deny policy while preserving allowed nulls`() {
        val policy = DataFieldPolicy(
            unrestricted = false,
            allowed = setOf(
                "ATTACHMENTS", "NAVIGATION", "CLUSTER_SUMMARY",
                "ATTACHMENT_TYPE", "AVAILABLE", "PREVIOUS_ID", "CLUSTER_ID", "CLUSTER_NAME"
            ),
            denied = setOf("MIME_TYPE", "NEXT_ID", "DECISION_REASON")
        )
        val response = detailResponse(
            policy = policy,
            attachments = listOf(
                FeedbackAttachmentSummaryResponse(
                    id = 8,
                    attachmentType = "IMAGE",
                    mimeType = "image/png",
                    fileSizeBytes = null,
                    width = null,
                    height = null,
                    durationSeconds = null,
                    uploadStatus = null,
                    securityStatus = null,
                    integrityStatus = null,
                    decryptionStatus = null,
                    malwareScanStatus = null,
                    contentReviewStatus = null,
                    sourceKind = null,
                    retentionUntil = null,
                    createdAt = null,
                    available = true,
                    fieldPolicy = policy
                )
            ),
            navigation = FeedbackNavigationResponse(6, 9, policy),
            cluster = FeedbackClusterSummaryResponse(3, null, 4, null, "禁止理由", "v1", policy)
        )

        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
        assertTrue(tree.has("attachments"))
        assertTrue(tree.has("navigation"))
        assertTrue(tree.has("clusterSummary"))
        assertTrue(tree["attachments"][0].has("attachmentType"))
        assertTrue(tree["attachments"][0].has("available"))
        assertFalse(tree["attachments"][0].has("mimeType"))
        assertTrue(tree["navigation"].has("previousId"))
        assertFalse(tree["navigation"].has("nextId"))
        assertTrue(tree["clusterSummary"].has("clusterId"))
        assertTrue(tree["clusterSummary"].has("clusterName"))
        assertTrue(tree["clusterSummary"]["clusterName"].isNull)
        assertFalse(tree["clusterSummary"].has("decisionReason"))
        assertFalse(tree.toString().contains("fieldPolicy"))
    }

    @Test
    fun `standalone attachment DTO applies deny policy and preserves an allowed null`() {
        val policy = DataFieldPolicy(
            unrestricted = false,
            allowed = setOf("MIME_TYPE", "AVAILABLE"),
            denied = setOf("ATTACHMENT_TYPE", "FILE_SIZE_BYTES")
        )
        val response = FeedbackAttachmentSummaryResponse(
            id = 8,
            attachmentType = "IMAGE",
            mimeType = null,
            fileSizeBytes = 2048,
            width = null,
            height = null,
            durationSeconds = null,
            uploadStatus = null,
            securityStatus = null,
            integrityStatus = null,
            decryptionStatus = null,
            malwareScanStatus = null,
            contentReviewStatus = null,
            sourceKind = null,
            retentionUntil = null,
            createdAt = null,
            available = true,
            fieldPolicy = policy
        )

        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
        assertTrue(tree.has("id"))
        assertTrue(tree.has("mimeType"))
        assertTrue(tree["mimeType"].isNull)
        assertTrue(tree.has("available"))
        assertFalse(tree.has("attachmentType"))
        assertFalse(tree.has("fileSizeBytes"))
        assertFalse(tree.has("fieldPolicy"))
    }

    @Suppress("LongMethod")
    private fun detailResponse(
        policy: DataFieldPolicy,
        attachments: List<FeedbackAttachmentSummaryResponse>,
        navigation: FeedbackNavigationResponse,
        cluster: FeedbackClusterSummaryResponse
    ) = FeedbackDetailResponse(
        id = 1,
        sourceChannel = "jira",
        sourceRecordId = "FP-1",
        appVersion = null,
        reportedAt = null,
        faultTimePrecision = null,
        faultTimeSource = null,
        faultTimeResolution = null,
        ingestTime = null,
        sourceUpdatedAt = null,
        importBatchId = null,
        title = "标题",
        description = null,
        module = "COURSE",
        usageStage = null,
        problemType = null,
        issueCategory = null,
        severity = null,
        canonicalSeverity = null,
        severityProvisional = false,
        severityReviewStatus = null,
        emotion = null,
        reproducibility = null,
        status = "NEW",
        distributionStatus = "UNASSIGNED",
        maskingStatus = "PASS",
        platformUpdatedAt = null,
        statusMappingVersion = null,
        statusMappingResult = null,
        statusMappedAt = null,
        mappingRule = null,
        normalizationVersion = null,
        qualityScore = null,
        qualityRuleVersion = null,
        importBatchCode = null,
        importBatchStatus = null,
        ownerId = null,
        ownerName = null,
        assignmentVersion = null,
        assignedAt = null,
        region = "",
        deviceInfo = null,
        externalLinkAvailable = false,
        channelMeta = null,
        diagnosticAvailability = FeedbackDiagnosticAvailabilityResponse(false, emptyList(), false, policy),
        fieldHistory = emptyList(),
        attachments = attachments,
        navigation = navigation,
        clusterSummary = cluster,
        aiResult = null,
        fieldPolicy = policy
    )

    private data class ScopedDetail(
        val id: Long,
        val title: String,
        val description: String,
        val analysis: ScopedAnalysis,
        override val fieldPolicy: DataFieldPolicy
    ) : FieldScopedResponse

    private data class ScopedAnalysis(
        val summary: String,
        val reasoning: String,
        override val fieldPolicy: DataFieldPolicy
    ) : FieldScopedResponse
}
