package com.feedback.architecture

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.FeishuSsoProperties
import com.feedback.dto.sdk.SdkTelemetryRequest
import com.feedback.dto.sdk.TelemetryEventDto
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.mapper.jira.JiraSyncStateMapper
import com.feedback.model.po.jira.JiraSyncStatePo
import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import com.feedback.repository.impl.attachment.MyBatisFeedbackAttachmentMutationRepositoryImpl
import com.feedback.repository.auth.AuthExternalIdentityRepository
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthRoleCatalogRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.FeishuPolicyRepository
import com.feedback.repository.auth.FeishuRoleMappingRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.repository.jira.JiraSyncStateRepository
import com.feedback.repository.sdk.SdkTelemetryRepository
import com.feedback.security.WebPrincipal
import com.feedback.service.auth.FeishuAuthorizationPolicyService
import com.feedback.repository.remote.auth.FeishuOrganizationClient
import com.feedback.model.auth.FeishuPolicyPreviewInput
import com.feedback.model.auth.FeishuRoleMappingInput
import com.feedback.service.auth.FeishuRoleMappingService
import com.feedback.service.impl.auth.FeishuAuthorizationPolicyServiceImpl
import com.feedback.service.impl.auth.FeishuRoleMappingServiceImpl
import com.feedback.manager.sdk.SdkTelemetryCommitWorker
import com.feedback.service.impl.sdk.SdkTelemetryServiceImpl
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

/** 冻结当前五个生产调用链经 Repository 收口后的业务行为。 */
class P0RepositoryLayeringContractTest {
    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `role mapping creation persists a normalized draft and appends audit through repositories`() {
        val mappings = mockk<FeishuRoleMappingRepository>()
        val audits = mockk<SystemAuditMutationRepository>()
        val stored = slot<AuthFeishuRoleMappingPo>()
        every { mappings.create(capture(stored)) } returns 7L
        every { audits.append(any()) } just runs
        authenticate(21)
        val service = FeishuRoleMappingServiceImpl(
            mappings,
            mockk<AuthRoleCatalogRepository>(),
            audits,
            objectMapper
        )

        val result = service.create(
            FeishuRoleMappingInput("USER_GROUP", " group-1 ", " 反馈运营组 ", "operator", 20)
        )

        assertEquals(7L, result["id"])
        assertEquals("DRAFT", result["decisionStatus"])
        assertEquals("group-1", stored.captured.subjectId)
        assertEquals("OPERATOR", stored.captured.roleCode)
        verify(exactly = 1) { audits.append(match { it.targetId == "7" && it.outcome == "SUCCESS" }) }
    }

    @Test
    fun `policy preview evaluates only approved typed mappings`() {
        val mappings = mockk<FeishuRoleMappingRepository>()
        every { mappings.approved() } returns listOf(
            mapping(1, "DEFAULT", "*", "ROLE", "USER", null, null, 100),
            mapping(2, "USER_GROUP", "group-ops", "ROLE", "OPERATOR", null, null, 20),
            mapping(3, "DEPARTMENT", "dept-sdk", "DATA_SCOPE", null, "CHANNEL", "SDK", 30)
        )
        val service = FeishuAuthorizationPolicyServiceImpl(
            mockk<FeishuPolicyRepository>(),
            mappings,
            mockk<AuthExternalIdentityRepository>(),
            mockk<AuthUserRepository>(),
            mockk<AuthGrantRepository>(),
            mockk<SystemAuditMutationRepository>(),
            objectMapper,
            FeishuSsoProperties(),
            mockk<FeishuOrganizationClient>(),
            mockk<TransactionTemplate>()
        )

        val result = service.preview(
            FeishuPolicyPreviewInput(groupIds = setOf("group-ops"), departmentIds = setOf("dept-sdk"))
        )

        assertEquals("OPERATOR", result["roleCode"])
        assertEquals(1, (result["dataScopes"] as List<*>).size)
        assertEquals(3, result["matchedRuleCount"])
    }

    @Test
    fun `SDK telemetry keeps one bounded repository batch and excludes raw payload`() {
        val repository = mockk<SdkTelemetryRepository>()
        val rows = slot<List<SdkTransportTelemetryPo>>()
        every { repository.insertTransportEvents(capture(rows)) } just runs
        val service = SdkTelemetryServiceImpl(repository, objectMapper, SdkTelemetryCommitWorker(repository))

        service.ingestTelemetry(
            SdkTelemetryRequest(
                events = listOf(
                    TelemetryEventDto(
                        clientFeedbackId = "client-1",
                        failureStage = "ATTACHMENT_UPLOAD",
                        errorType = "HTTP_500",
                        networkType = "WIFI",
                        retryCount = 2,
                        attachmentSizeBytes = 4096,
                        sdkVersion = "1.0.0",
                        appVersion = "1.1.0",
                        durationMs = 1234
                    )
                )
            ),
            appKey = "course",
            clientIp = "127.0.0.1"
        )

        verify(exactly = 1) { repository.insertTransportEvents(any()) }
        assertEquals(1, rows.captured.size)
        assertEquals("client-1", rows.captured.single().targetId)
        assertEquals("127.0.0.1", rows.captured.single().ipAddress)
        val detail = objectMapper.readTree(rows.captured.single().detailJson)
        assertEquals("course", detail["app_key"].asText())
        assertEquals("ATTACHMENT_UPLOAD", detail["failure_stage"].asText())
        assertEquals(2, detail["retry_count"].asInt())
        assertEquals(null, detail["raw_payload"])
    }

    @Test
    fun `attachment repository resolves registered paths with one bounded mapper call`() {
        val mapper = mockk<FeedbackAttachmentMapper>()
        val candidates = listOf("feedback/1/a.png", "feedback/2/b.png")
        every { mapper.findRegisteredStoragePaths(candidates) } returns listOf("feedback/2/b.png")
        val repository = MyBatisFeedbackAttachmentMutationRepositoryImpl(mapper)

        val result = repository.registeredStoragePaths(candidates)

        assertEquals(setOf("feedback/2/b.png"), result)
        verify(exactly = 1) { mapper.findRegisteredStoragePaths(candidates) }
    }

    @Test
    fun `Jira state claim reads the claimed state only after conditional update`() {
        val mapper = mockk<JiraSyncStateMapper>()
        val attemptedAt = LocalDateTime.of(2026, 9, 5, 10, 30)
        every { mapper.claim("primary", "worker-1") } returns 1
        every { mapper.find("primary") } returns JiraSyncStatePo(
            connectionKey = "primary",
            status = "RUNNING",
            watermark = null,
            requestedFrom = null,
            requestedTo = null,
            requestedTrigger = "MANUAL",
            requestedBy = 21,
            lastAttemptAt = attemptedAt,
            lastSuccessAt = null,
            nextRunAt = null,
            lastBatchId = null,
            failureCount = 0,
            lastErrorCode = null,
            lastErrorDetail = null
        )
        val repository = JiraSyncStateRepository(mapper)

        val result = repository.claim("primary", "worker-1")

        assertEquals("RUNNING", result?.status)
        assertEquals(attemptedAt, result?.lastAttemptAt)
        verifyOrder {
            mapper.claim("primary", "worker-1")
            mapper.find("primary")
        }
    }

    private fun authenticate(userId: Long) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            WebPrincipal(userId, "owner", "", "项目负责人", setOf("ADMIN"), emptySet()),
            null,
            emptyList()
        )
    }

    private fun mapping(
        id: Long,
        ruleType: String,
        subjectId: String,
        grantKind: String,
        roleCode: String?,
        scopeType: String?,
        scopeValue: String?,
        priority: Int
    ) = AuthFeishuRoleMappingPo(
        id = id,
        ruleType = ruleType,
        subjectId = subjectId,
        subjectName = subjectId,
        grantKind = grantKind,
        roleCode = roleCode,
        scopeType = scopeType,
        scopeValue = scopeValue,
        priority = priority,
        description = null,
        createdBy = 21
    )
}
