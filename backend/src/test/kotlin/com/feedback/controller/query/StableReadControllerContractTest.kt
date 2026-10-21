package com.feedback.controller.query

import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.config.JacksonConfig
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentDeliveryMode
import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import com.feedback.model.attachment.SecureAttachmentContent
import com.feedback.model.attachment.AttachmentContentSource
import com.feedback.service.query.FeedbackDiagnosticsApplicationService
import com.feedback.service.query.RelatedFeedbackApplicationService
import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentPreviewsCommand
import com.feedback.model.attachment.AttachmentPreviewAuthorizationItem
import com.feedback.model.attachment.AttachmentPreviewAuthorizationResponse
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeMediaPreviewCommand
import com.feedback.model.attachment.FeedbackAttachmentListQuery
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.feedback.service.query.detail.FeedbackDetailResponse
import com.feedback.service.query.detail.FeedbackExternalLinkResponse
import com.feedback.service.query.detail.FeedbackFieldHistoryResponse
import com.feedback.service.query.detail.FeedbackTimelineEventResponse
import io.swagger.v3.oas.annotations.media.Schema
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.ResourceHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDateTime

/** 只冻结已经过治理的反馈读取/证据 HTTP 契约。 */
class StableReadControllerContractTest {
    private val listReads = mockk<FeedbackListQueryUseCase>()
    private val detailReads = mockk<FeedbackDetailTestDouble>()
    private val relatedReads = mockk<RelatedFeedbackApplicationService>()
    private val attachmentReads = mockk<FeedbackAttachmentTestDouble>()
    private val diagnosticsReads = mockk<FeedbackDiagnosticsApplicationService>()
    private val secureStream = mockk<AttachmentSecureStreamService>()
    private val mapper = JacksonConfig().objectMapper().findAndRegisterModules()
    private val mvc = MockMvcBuilders.standaloneSetup(
        FeedbackListController(listReads),
        FeedbackDetailController(detailReads, detailReads, detailReads, detailReads),
        FeedbackController(relatedReads, diagnosticsReads),
        FeedbackAttachmentController(attachmentReads, secureStream)
    )
        .setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .setMessageConverters(
            MappingJackson2HttpMessageConverter(mapper),
            ResourceHttpMessageConverter()
        )
        .build()
    private val policy = DataFieldPolicy(true, emptySet(), emptySet())

    @Test
    fun `stable controller methods retain named public response types`() {
        val detailSignatures = FeedbackDetailController::class.java.methods.associateBy { it.name }
        val evidenceSignatures = FeedbackAttachmentController::class.java.methods.associateBy { it.name }

        assertTrue(detailSignatures.getValue("detail").genericReturnType.typeName.contains(FeedbackDetailResponse::class.java.name))
        assertTrue(detailSignatures.getValue("modifications").genericReturnType.typeName.contains(FeedbackFieldHistoryResponse::class.java.name))
        assertTrue(detailSignatures.getValue("timeline").genericReturnType.typeName.contains(FeedbackTimelineEventResponse::class.java.name))
        assertTrue(evidenceSignatures.getValue("attachments").genericReturnType.typeName.contains(FeedbackAttachmentSummaryResponse::class.java.name))
        assertTrue(detailSignatures.getValue("externalLink").genericReturnType.typeName.contains(FeedbackExternalLinkResponse::class.java.name))
    }

    @Test
    fun `attachment grant exposes field-level OpenAPI security and expiry contracts`() {
        assertTrue(AttachmentAccessGrant::class.java.getAnnotation(Schema::class.java) != null)
        val schemas = AttachmentAccessGrant::class.java.declaredFields.associate { field ->
            field.name to field.getAnnotation(Schema::class.java)
        }

        assertTrue(schemas.keys.containsAll(setOf("grantId", "expiresAt", "expiresInSeconds", "delivery", "downloadUrl")))
        assertTrue(schemas.values.all { it != null })
        assertTrue(schemas.getValue("grantId")!!.description.contains("不得写入日志"))
        assertTrue(schemas.getValue("expiresAt")!!.description.contains("Asia/Shanghai"))
        assertTrue(schemas.getValue("expiresInSeconds")!!.minimum == "30")
        assertTrue(schemas.getValue("expiresInSeconds")!!.maximum == "300")
        assertTrue("ONE_TIME_SECURE_STREAM" in schemas.getValue("delivery")!!.allowableValues)
    }

    @Test
    fun `field history timeline attachment and external link preserve safe JSON contracts`() {
        every { detailReads.modifications(7) } returns listOf(
            FeedbackFieldHistoryResponse(
                3, "module", "旧模块", "新模块", "MANUAL_CORRECTION", "USER", 9,
                "mapping-v1", "normalization-v1", "纠正映射", LocalDateTime.of(2026, 8, 30, 10, 0), "审核员", policy
            )
        )
        every { detailReads.timeline(7) } returns listOf(
            FeedbackTimelineEventResponse(
                "field-3", LocalDateTime.of(2026, 8, 30, 10, 0), "人工修正", "module: 旧模块 → 新模块", fieldPolicy = policy
            )
        )
        every { attachmentReads.list(FeedbackAttachmentListQuery(7)) } returns listOf(
            FeedbackAttachmentSummaryResponse(
                id = 11,
                attachmentType = "SCREENSHOT",
                mimeType = "image/png",
                fileSizeBytes = 2048,
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
                retentionUntil = null,
                createdAt = LocalDateTime.of(2026, 8, 30, 9, 0),
                available = true,
                fieldPolicy = policy
            )
        )
        every { detailReads.externalLink(7) } returns FeedbackExternalLinkResponse("https://jira.example/browse/FP-7", "JIRA")

        mvc.get("/api/v1/feedback/7/modifications").andExpect {
            status { isOk() }
            jsonPath("$.data[0].fieldName") { value("module") }
            jsonPath("$.data[0].operator") { value("审核员") }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
        }
        mvc.get("/api/v1/feedback/7/timeline").andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value("field-3") }
            jsonPath("$.data[0].type") { value("primary") }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
        }
        mvc.get("/api/v1/feedback/7/attachments").andExpect {
            status { isOk() }
            jsonPath("$.data[0].id") { value(11) }
            jsonPath("$.data[0].durationSeconds") { value(nullValue()) }
            jsonPath("$.data[0].storagePath") { doesNotExist() }
            jsonPath("$.data[0].ciphertext") { doesNotExist() }
            jsonPath("$.data[0].fieldPolicy") { doesNotExist() }
        }
        mvc.get("/api/v1/feedback/7/external-link").andExpect {
            status { isOk() }
            jsonPath("$.data.url") { value("https://jira.example/browse/FP-7") }
            jsonPath("$.data.readOnly") { value(true) }
        }
    }

    @Test
    fun `attachment authorization and stream retain one-time no-store delivery contract`() {
        every { attachmentReads.authorize(any<AuthorizeFeedbackAttachmentCommand>()) } returns AttachmentAccessGrant(
            "grant-token", LocalDateTime.of(2026, 8, 30, 10, 5), 300,
            AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM,
            "/api/v1/feedback/7/attachments/11/stream?grantId=grant-token"
        )
        every { secureStream.stream(ConsumeFeedbackAttachmentCommand(7, 11, "grant-token", null)) } returns SecureAttachmentContent(
            AttachmentContentSource { "safe-image".byteInputStream() }, "image/png", "evidence.png", 10
        )

        mvc.post("/api/v1/feedback/7/attachments/11/authorize").andExpect {
            status { isOk() }
            jsonPath("$.data.grantId") { value("grant-token") }
            jsonPath("$.data.delivery") { value("ONE_TIME_SECURE_STREAM") }
            jsonPath("$.data.downloadUrl") { value("/api/v1/feedback/7/attachments/11/stream?grantId=grant-token") }
        }
        mvc.get("/api/v1/feedback/7/attachments/11/stream") { param("grantId", "grant-token") }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("Content-Disposition", containsString("filename*=UTF-8''evidence.png")) }
            header { string("Content-Length", "10") }
        }
    }

    @Test
    fun `derived media preview uses isolated grant and no-store jpeg stream`() {
        every { attachmentReads.authorize(AuthorizeFeedbackAttachmentCommand(7, 11, AttachmentGrantPurpose.DERIVED_PREVIEW)) } returns AttachmentAccessGrant(
            "preview-token", LocalDateTime.of(2026, 8, 30, 10, 5), 300,
            AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW,
            "/api/v1/feedback/7/attachments/11/preview?grantId=preview-token"
        )
        every { secureStream.preview(ConsumeMediaPreviewCommand(7, 11, "preview-token")) } returns SecureAttachmentContent(
            AttachmentContentSource { byteArrayOf(1, 2, 3).inputStream() }, "image/jpeg", "preview.jpg", 3
        )

        mvc.post("/api/v1/feedback/7/attachments/11/authorize-preview").andExpect {
            status { isOk() }
            jsonPath("$.data.delivery") { value("ONE_TIME_DERIVED_PREVIEW") }
        }
        mvc.get("/api/v1/feedback/7/attachments/11/preview") { param("grantId", "preview-token") }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            header { string("Content-Type", "image/jpeg") }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("Content-Length", "3") }
        }
    }

    @Test
    fun `visible media previews use one bounded batch authorization request`() {
        val grant = AttachmentAccessGrant(
            "preview-token", LocalDateTime.of(2026, 8, 30, 10, 5), 300,
            AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW,
            "/api/v1/feedback/7/attachments/11/preview?grantId=preview-token"
        )
        every {
            attachmentReads.authorizePreviews(AuthorizeFeedbackAttachmentPreviewsCommand(7, listOf(11, 12)))
        } returns AttachmentPreviewAuthorizationResponse(listOf(
            AttachmentPreviewAuthorizationItem(11, com.feedback.common.AttachmentPreviewStatus.AVAILABLE, grant),
            AttachmentPreviewAuthorizationItem(12, com.feedback.common.AttachmentPreviewStatus.PENDING)
        ))

        mvc.post("/api/v1/feedback/7/attachments/authorize-previews") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"attachmentIds":[11,12]}"""
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.data.items[0].grant.delivery") { value("ONE_TIME_DERIVED_PREVIEW") }
            jsonPath("$.data.items[1].status") { value("PENDING") }
        }
    }
}
