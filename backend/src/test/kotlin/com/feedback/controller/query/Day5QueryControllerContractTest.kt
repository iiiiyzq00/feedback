package com.feedback.controller.query

import com.feedback.common.AttachmentGrantPurpose
import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.config.JacksonConfig
import com.feedback.service.query.ClusterReviewService
import com.feedback.service.query.FeedbackDiagnosticsApplicationService
import com.feedback.service.query.RelatedFeedbackApplicationService
import com.feedback.service.query.SearchSuggestionApplicationService
import com.feedback.service.query.SimilarFeedbackService
import com.feedback.service.query.FeedbackDiagnosticItemResponse
import com.feedback.service.query.FeedbackDiagnosticsResponse
import com.feedback.service.query.SearchSuggestionChannelCountResponse
import com.feedback.service.query.SearchSuggestionItemResponse
import com.feedback.service.query.SearchSuggestionResponse
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentDeliveryMode
import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import com.feedback.model.attachment.SecureAttachmentContent
import com.feedback.model.attachment.AttachmentContentSource
import com.feedback.service.cluster.ClusterLifecycleService
import com.feedback.service.cluster.ClusterReviewGovernance
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.query.detail.*
import com.feedback.service.query.listprojection.FeedbackListResponse
import com.feedback.service.query.listprojection.FeedbackListStatsResponse
import com.feedback.service.query.listprojection.FeedbackExportUseCase
import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.FeedbackAttachmentListQuery
import com.feedback.service.attachment.FeedbackAttachmentApplicationService
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.hamcrest.Matchers.nullValue
import java.time.LocalDateTime
import java.io.ByteArrayInputStream
import com.feedback.service.query.analysis.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class Day5QueryControllerContractTest {
    private val listReads = mockk<FeedbackListQueryUseCase>()
    private val exports = mockk<FeedbackExportUseCase>()
    private val detailReads = mockk<FeedbackDetailTestDouble>()
    private val relatedReads = mockk<RelatedFeedbackApplicationService>()
    private val attachmentReads = mockk<FeedbackAttachmentTestDouble>()
    private val attachmentStreams = mockk<AttachmentSecureStreamService>()
    private val diagnosticsReads = mockk<FeedbackDiagnosticsApplicationService>()
    private val suggestions = mockk<SearchSuggestionApplicationService>()
    private val clusters = mockk<ClusterReviewService>()
    private val clusterLifecycle = mockk<ClusterLifecycleService>(relaxed = true)
    private val similar = mockk<SimilarFeedbackService>()
    private val feedbackAnalysis = mockk<ProblemAnalysisQueryUseCase>()
    private val feedbackClusterReads = mockk<ClusterReadUseCase>()
    private val mapper = JacksonConfig().objectMapper().findAndRegisterModules()
    private val mvc = MockMvcBuilders.standaloneSetup(
        FeedbackListController(listReads),
        FeedbackDetailController(detailReads, detailReads, detailReads, detailReads),
        FeedbackController(relatedReads, diagnosticsReads),
        FeedbackAttachmentController(attachmentReads, attachmentStreams),
        FeedbackExportController(exports),
        ProblemAnalysisController(feedbackAnalysis), ClusterReadController(feedbackClusterReads),
        ClusterReviewController(clusters, clusterLifecycle, mockk<ClusterReviewGovernance>(relaxed = true)),
        SearchController(similar), SearchSuggestionController(suggestions)
    ).setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .setMessageConverters(StringHttpMessageConverter(), MappingJackson2HttpMessageConverter(mapper)).build()

    @Test
    fun `feedback list exposes projection unavailability as 503 without fallback`() {
        every { listReads.list(any()) } throws ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "FEEDBACK_FEEDBACK_LIST_READ_DISABLED"
        )

        mvc.get("/api/v1/feedback").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.details.reasonCode") { value("FEEDBACK_FEEDBACK_LIST_READ_DISABLED") }
        }
    }

    @Test
    fun `feedback list detail status and csv contracts`() {
        every { listReads.list(any()) } returns FeedbackListResponse(
            emptyList(), 0, 1, 20,
            FeedbackListStatsResponse(0, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0)
        )
        every { detailReads.detail(1) } returns detailContract()
        every { exports.exportSelection(listOf(1L), any()) } returns "id,title\n\"1\",\"测试\""

        mvc.get("/api/v1/feedback") { param("channel", "sdk"); param("page", "1") }.andExpect {
            status { isOk() }; jsonPath("$.data.total") { value(0) }
        }
        mvc.get("/api/v1/feedback/1").andExpect {
            status { isOk() }
            jsonPath("$.data.sourceChannel") { value("jira") }
            jsonPath("$.data.maskingStatus") { value("PASS") }
            jsonPath("$.data.region") { value("GD/SZ/NANSHAN") }
            jsonPath("$.data.channelMeta.jira_key") { value("SPFB-1") }
            jsonPath("$.data.diagnosticAvailability.types[1]") { value("APPLICATION_LOG") }
            jsonPath("$.data.fieldHistory[0].operator") { value("审核员") }
            jsonPath("$.data.navigation.previousId") { value(2) }
            jsonPath("$.data.navigation.nextId") { doesNotExist() }
            jsonPath("$.data.clusterSummary.clusterId") { value(3) }
            jsonPath("$.data.aiResult.confidence") { value(0.91) }
        }
        mvc.get("/api/v1/feedback/export") { param("ids", "1") }.andExpect {
            status { isOk() }; header { string("Content-Disposition", "attachment; filename=feedback.csv") }
        }
    }

    @Test
    fun `detail attachment authorization and external link contracts`() {
        val fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())
        every { attachmentReads.list(FeedbackAttachmentListQuery(1)) } returns listOf(
            FeedbackAttachmentSummaryResponse(
                id = 7L,
                attachmentType = "SCREENSHOT",
                mimeType = "image/png",
                fileSizeBytes = 2048L,
                width = 1280,
                height = 720,
                durationSeconds = null,
                uploadStatus = "VERIFIED",
                securityStatus = "VERIFIED",
                integrityStatus = "VERIFIED",
                decryptionStatus = "DECRYPTED",
                malwareScanStatus = "CLEAN",
                contentReviewStatus = "PASS",
                sourceKind = "JIRA",
                retentionUntil = LocalDateTime.of(2026, 9, 29, 16, 30),
                createdAt = LocalDateTime.of(2026, 8, 29, 16, 30),
                available = true,
                fieldPolicy = fieldPolicy
            ),
            FeedbackAttachmentSummaryResponse(
                id = 8L,
                attachmentType = "VIDEO",
                mimeType = "video/mp4",
                fileSizeBytes = 4096L,
                width = null,
                height = null,
                durationSeconds = 12,
                uploadStatus = "UPLOADED",
                securityStatus = "QUARANTINED",
                integrityStatus = "PENDING",
                decryptionStatus = "NOT_REQUIRED",
                malwareScanStatus = "PENDING",
                contentReviewStatus = "PENDING",
                sourceKind = "JIRA",
                retentionUntil = LocalDateTime.of(2026, 9, 29, 16, 30),
                createdAt = LocalDateTime.of(2026, 8, 29, 16, 31),
                available = false,
                fieldPolicy = fieldPolicy
            )
        )
        every { attachmentReads.authorize(AuthorizeFeedbackAttachmentCommand(1, 7, AttachmentGrantPurpose.INLINE_VIEW)) } returns AttachmentAccessGrant(
            grantId = "grant-1",
            expiresAt = LocalDateTime.of(2026, 8, 29, 16, 30),
            expiresInSeconds = 300,
            delivery = AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM,
            downloadUrl = "/api/v1/feedback/1/attachments/7/stream?grantId=grant-1"
        )
        every { detailReads.externalLink(1) } returns FeedbackExternalLinkResponse("https://jira.example/browse/FP-1", "JIRA")
        mvc.get("/api/v1/feedback/1/attachments").andExpect {
            status { isOk() }
            jsonPath("$.data.length()") { value(2) }
            jsonPath("$.data[0].id") { value(7) }
            jsonPath("$.data[0].attachmentType") { value("SCREENSHOT") }
            jsonPath("$.data[0].mimeType") { value("image/png") }
            jsonPath("$.data[0].fileSizeBytes") { value(2048) }
            jsonPath("$.data[0].width") { value(1280) }
            jsonPath("$.data[0].height") { value(720) }
            jsonPath("$.data[0].durationSeconds") { value(nullValue()) }
            jsonPath("$.data[0].available") { value(true) }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
            jsonPath("$.data[0].storagePath") { doesNotExist() }
            jsonPath("$.data[1].id") { value(8) }
            jsonPath("$.data[1].durationSeconds") { value(12) }
            jsonPath("$.data[1].available") { value(false) }
        }
        mvc.post("/api/v1/feedback/1/attachments/7/authorize").andExpect {
            status { isOk() }
            jsonPath("$.data.delivery") { value("ONE_TIME_SECURE_STREAM") }
            jsonPath("$.data.downloadUrl") { value("/api/v1/feedback/1/attachments/7/stream?grantId=grant-1") }
        }
        mvc.get("/api/v1/feedback/1/external-link").andExpect {
            status { isOk() }; jsonPath("$.data.readOnly") { value(true) }
        }
    }

    @Test
    fun `standalone attachment list applies field policy and preserves allowed null`() {
        val policy = DataFieldPolicy(
            unrestricted = false,
            allowed = setOf("MIME_TYPE", "AVAILABLE"),
            denied = setOf("ATTACHMENT_TYPE", "FILE_SIZE_BYTES")
        )
        every { attachmentReads.list(FeedbackAttachmentListQuery(1)) } returns listOf(
            FeedbackAttachmentSummaryResponse(
                id = 7,
                attachmentType = "SCREENSHOT",
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
        )

        mvc.get("/api/v1/feedback/1/attachments").andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(7) }
            jsonPath("$.data[0].mimeType") { value(nullValue()) }
            jsonPath("$.data[0].available") { value(true) }
            jsonPath("$.data[0].attachmentType") { doesNotExist() }
            jsonPath("$.data[0].fileSizeBytes") { doesNotExist() }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
        }
    }

    private fun detailContract(): FeedbackDetailResponse {
        val policy = DataFieldPolicy(true, emptySet(), emptySet())
        return FeedbackDetailResponse(
            id = 1,
            sourceChannel = "jira",
            sourceRecordId = "SPFB-1",
            appVersion = null,
            reportedAt = null,
            faultTimePrecision = null,
            faultTimeSource = null,
            faultTimeResolution = null,
            ingestTime = null,
            sourceUpdatedAt = null,
            importBatchId = null,
            title = "课程页面卡顿",
            description = "进入课程页面后出现卡顿",
            module = "课程",
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
            region = "GD/SZ/NANSHAN",
            deviceInfo = null,
            externalLinkAvailable = true,
            channelMeta = JiraFeedbackChannelMetaResponse(
                "SPFB-1", null, null, null, null, null, "张三", null, null, null,
                null, null, null, null, null, null, null, null, policy
            ),
            diagnosticAvailability = FeedbackDiagnosticAvailabilityResponse(
                true, listOf("DEVICE_SN", "APPLICATION_LOG"), false, policy
            ),
            fieldHistory = listOf(
                FeedbackFieldHistoryResponse(
                    9, "module", null, null, null, null, null, null, null,
                    null, null, "审核员", policy
                )
            ),
            attachments = emptyList(),
            navigation = FeedbackNavigationResponse(2, null, policy),
            clusterSummary = FeedbackClusterSummaryResponse(3, "课程卡顿", 4, null, null, null, policy),
            aiResult = FeedbackAiAnalysisResponse(
                "可能存在课程性能问题", null, null, null, null, null, null, null,
                null, java.math.BigDecimal("0.91"), null, null, null, null, policy
            ),
            fieldPolicy = policy
        )
    }

    @Test
    fun `secure attachment stream exposes the authoritative content length`() {
        val secureStream = mockk<AttachmentSecureStreamService>()
        every { secureStream.stream(ConsumeFeedbackAttachmentCommand(1, 7, "g".repeat(43), null)) } returns SecureAttachmentContent(
            AttachmentContentSource { byteArrayOf(1, 2, 3).inputStream() }, "application/gzip", "attachment-7.gz", 3L
        )
        val response = FeedbackAttachmentController(
            attachmentReads, secureStream
        ).streamAttachment(1, 7, "g".repeat(43), null)
        assertEquals(3L, response.headers.contentLength)
        assertEquals("no-store", response.headers.cacheControl)
    }

    @Test
    fun `http resource close releases the application stream`() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        val streams = mockk<AttachmentSecureStreamService>()
        every { streams.stream(ConsumeFeedbackAttachmentCommand(1, 7, "g".repeat(43), null)) } returns
            SecureAttachmentContent(AttachmentContentSource { input }, "application/octet-stream", "evidence.bin", 3)

        val response = FeedbackAttachmentController(attachmentReads, streams)
            .streamAttachment(1, 7, "g".repeat(43), null)
        response.body!!.inputStream.use { it.readAllBytes() }

        assertEquals(true, input.closed)
    }

    @Test
    fun `diagnostic access is direct role based and non cacheable`() {
        every { diagnosticsReads.viewDirect(1, any()) } returns FeedbackDiagnosticsResponse(
            1L, listOf(FeedbackDiagnosticItemResponse("DEVICE_SN", "SN-001", "ENCRYPTED_VAULT")), "RAW"
        )
        mvc.get("/api/v1/feedback/1/diagnostics").andExpect {
            status { isOk() }; header { string("Cache-Control", "no-store") }
            jsonPath("$.data.diagnostics[0].type") { value("DEVICE_SN") }
            jsonPath("$.data.contentVisibility") { value("RAW") }
            jsonPath("$.data.responseCacheable") { value(false) }
        }
    }

    @Test
    fun `feedback timeline keeps the existing public event contract`() {
        every { detailReads.timeline(1) } returns listOf(
            FeedbackTimelineEventResponse(
                "ingest", LocalDateTime.of(2026, 8, 30, 9, 0),
                "原始数据导入", "实时接入", fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())
            ),
            FeedbackTimelineEventResponse(
                "cluster-7", LocalDateTime.of(2026, 8, 30, 9, 5),
                "问题归簇", "簇 #3 · APPROVED", fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())
            )
        )

        mvc.get("/api/v1/feedback/1/timeline").andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value("ingest") }
            jsonPath("$.data[0].time") { value("2026-08-30T09:00:00") }
            jsonPath("$.data[0].action") { value("原始数据导入") }
            jsonPath("$.data[0].detail") { value("实时接入") }
            jsonPath("$.data[0].type") { value("primary") }
            jsonPath("$.data[1].id") { value("cluster-7") }
        }
    }

    @Test
    fun `feedback timeline applies the request field policy to typed events`() {
        every { detailReads.timeline(1) } returns listOf(
            FeedbackTimelineEventResponse(
                "ingest",
                LocalDateTime.of(2026, 8, 30, 9, 0),
                "原始数据导入",
                "不得泄漏的详情",
                fieldPolicy = DataFieldPolicy(
                    unrestricted = false,
                    allowed = setOf("TIME", "ACTION", "TYPE"),
                    denied = setOf("DETAIL")
                )
            )
        )

        mvc.get("/api/v1/feedback/1/timeline").andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value("ingest") }
            jsonPath("$.data[0].time") { value("2026-08-30T09:00:00") }
            jsonPath("$.data[0].action") { value("原始数据导入") }
            jsonPath("$.data[0].type") { value("primary") }
            jsonPath("$.data[0].detail") { doesNotExist() }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
        }
    }

    @Test
    fun `problem analysis contract`() {
        every { feedbackAnalysis.analyze(ProblemAnalysisQuery(
            ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 20)
        )) } returns ProblemAnalysisResponse(
            ProblemClusterPageResponse(listOf(ProblemClusterItemResponse(
                1, "问题现象", 1, null, 0, emptyList(), null, null, 0, 0, 0, emptyList()
            )), 1, 1, 20),
            ProblemClusterPageResponse(emptyList(), 0, 1, 20), null, null, LocalDateTime.now()
        )
        mvc.get("/api/v1/dashboard/problem-analysis").andExpect {
            status { isOk() }
            jsonPath("$.data.allClusters.total") { value(1) }
            jsonPath("$.data.crossChannelClusters.total") { value(0) }
            jsonPath("$.data.metricVersion") { value("feedback-problem-analysis-v1") }
        }
    }

    @Test
    fun `search suggestion contract`() {
        every { suggestions.suggestions("崩溃", 10) } returns SearchSuggestionResponse(
            listOf(SearchSuggestionItemResponse("课程崩溃", "课程崩溃", "课程", "SDK", path = "/channel/1", id = 1)),
            listOf(SearchSuggestionChannelCountResponse("sdk", 1)), 1, "崩溃"
        )
        mvc.get("/api/v1/search/suggest") { param("q", "崩溃") }.andExpect {
            status { isOk() }; jsonPath("$.data.items[0].value") { value("课程崩溃") }; jsonPath("$.data.channelCounts[0].count") { value(1) }
        }
    }

    @Test
    fun `similar feedback contract`() {
        every { similar.search("摄像头课程崩溃", 10) } returns mapOf(
            "query" to "摄像头课程崩溃", "topK" to 10, "count" to 1,
            "items" to listOf(mapOf("sourceId" to 7L, "score" to 0.93f)), "modelVersion" to "bge-m3"
        )
        mvc.get("/api/v1/search/similar") { param("q", "摄像头课程崩溃"); param("topK", "10") }.andExpect {
            status { isOk() }; jsonPath("$.data.count") { value(1) }; jsonPath("$.data.items[0].sourceId") { value(7) }
        }
    }

    @Test
    fun `cluster review contracts`() {
        val authPrincipal = UsernamePasswordAuthenticationToken(WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), "")
        every { clusters.reviews("P1", 1, 20) } returns com.feedback.service.query.ClusterReviewPage(emptyList(), 0L, 1, 20)
        every { clusters.confirm(9, 3, any(), 1) } just runs
        every { clusters.reviewBatch(any(), 1) } returns 1
        every { clusters.reject(9, any(), 1) } just runs
        every { clusters.skip(9, any(), 1) } just runs
        every { clusters.unlink(9, any(), 1) } just runs
        every { clusters.create(9, "新问题簇", any(), 1) } returns 4
        mvc.get("/api/v1/cluster/review") { param("priority", "P1") }.andExpect { status { isOk() }; jsonPath("$.data.total") { value(0) } }
        val command = """"expectedVersion":0,"requestId":"00000000-0000-0000-0000-000000000001","reason":"审核确认依据""""
        mvc.post("/api/v1/cluster/review/9/confirm") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"clusterId":3,$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.confirmed") { value(true) } }
        mvc.post("/api/v1/cluster/review/batch") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """[{"sourceId":9,"action":"REJECT",$command}]""" }.andExpect { status { isOk() }; jsonPath("$.data.reviewed") { value(1) } }
        mvc.post("/api/v1/cluster/review/create") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"sourceId":9,"name":"新问题簇",$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.clusterId") { value(4) } }
        mvc.post("/api/v1/cluster/review/9/reject") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.rejected") { value(true) } }
        mvc.post("/api/v1/cluster/review/9/skip") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.skipped") { value(true) } }
        mvc.post("/api/v1/cluster/review/9/unlink") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.unlinked") { value(true) } }
        mvc.post("/api/v1/cluster/3/status") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"status":"MITIGATING",$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.changed") { value(true) } }
        mvc.post("/api/v1/cluster/3/merge") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"sourceClusterIds":[2],$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.merged") { value(true) } }
        every { clusterLifecycle.split(3, any(), any(), 1) } returns listOf(4, 5)
        mvc.post("/api/v1/cluster/3/split") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"partitions":[{"name":"视频卡顿","sourceIds":[8]},{"name":"视频无响应","sourceIds":[9]}],$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.clusterIds[0]") { value(4) }; jsonPath("$.data.clusterIds[1]") { value(5) } }
        mvc.post("/api/v1/cluster/3/reopen") { with { it.userPrincipal = authPrincipal; it }; contentType = MediaType.APPLICATION_JSON; content = """{"recurrenceSourceId":9,$command}""" }.andExpect { status { isOk() }; jsonPath("$.data.reopened") { value(true) } }
    }
}

interface FeedbackAttachmentTestDouble : FeedbackAttachmentApplicationService
