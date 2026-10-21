package com.feedback.service.query.detail

import com.feedback.model.auth.*
import com.feedback.service.auth.DataPermissionService
import com.feedback.application.port.feedback.detail.*
import com.feedback.common.exception.ClientException
import com.feedback.repository.feedback.access.FeedbackAccessFacts
import com.feedback.repository.feedback.access.FeedbackAccessRepository
import com.feedback.security.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackDetailApplicationServiceTest {
    private val accessRepository = mockk<FeedbackAccessRepository>()
    private val permissions = mockk<DataPermissionService>()
    private val principals = mockk<CurrentWebPrincipalProvider>()
    private val access = FeedbackDetailAccessContextProvider(
        FeedbackAccessGuard(accessRepository, permissions, principals), permissions, principals
    )
    private val externalReads = mockk<FeedbackExternalLinkReadPort>()
    private val config = mockk<FeedbackExternalLinkConfigurationPort>()
    private val audits = mockk<FeedbackDetailAuditPort>()
    private val externalLinks = FeedbackExternalLinkResolver(config, ExternalLinkPolicy())
    private val externalSnapshots = AuthorizedFeedbackExternalLinkSnapshotReader(access, externalReads, externalLinks)
    private val externalService = FeedbackExternalLinkQueryService(externalSnapshots, audits)

    @Test
    fun `jira external link validates and writes unchanged audit fact`() {
        authorize(setOf("OPERATOR"), "JIRA")
        every { externalReads.source(11) } returns FeedbackExternalSourceFact("JIRA")
        every { externalReads.channel(11, "JIRA") } returns JiraExternalLinkFacts("FP-11")
        every { config.jiraBaseUrl() } returns "https://jira.example"
        every { audits.append(any()) } just Runs

        val result = externalService.externalLink(11)

        assertEquals("https://jira.example/browse/FP-11", result.url)
        verify(exactly = 1) {
            audits.append(FeedbackDetailAuditEvent.ExternalLinkViewed(9, 11, "JIRA", "jira.example"))
        }
    }

    @Test
    fun `jira role rejection happens before channel and configuration lookup`() {
        authorize(setOf("PHASE_ONE_PRODUCT"), "JIRA")
        every { externalReads.source(11) } returns FeedbackExternalSourceFact("JIRA")

        assertThrows(ClientException::class.java) { externalService.externalLink(11) }

        verify(exactly = 0) { externalReads.channel(any(), any()) }
        verify(exactly = 0) { config.jiraBaseUrl() }
        verify(exactly = 0) { audits.append(any()) }
    }

    @Test
    fun `redblack unsafe URI fails closed without audit`() {
        authorize(setOf("PHASE_ONE_PRODUCT"), "REDBLACK")
        every { externalReads.source(12) } returns FeedbackExternalSourceFact("REDBLACK")
        every { externalReads.channel(12, "REDBLACK") } returns RedblackExternalLinkFacts(
            "https://user:secret@example.com/item"
        )

        assertThrows(ClientException::class.java) { externalService.externalLink(12) }
        verify(exactly = 0) { audits.append(any()) }
    }

    @Test
    fun `missing feedback closes before modification facts and field policy`() {
        every { principals.require() } returns principal(setOf("OPERATOR"))
        every { accessRepository.findBySourceId(404) } returns null
        val reads = mockk<FeedbackModificationReadPort>()
        val service = FeedbackModificationQueryService(AuthorizedFeedbackModificationSnapshotReader(access, reads))

        assertThrows(ClientException::class.java) { service.modifications(404) }

        verify(exactly = 0) { reads.readModifications(any()) }
        verify(exactly = 0) { permissions.fieldPolicy() }
    }

    @Test
    fun `field history filters denied real field and resolves named values`() {
        authorize(setOf("OPERATOR"), "JIRA")
        val policy = DataFieldPolicy(unrestricted = true, allowed = emptySet(), denied = setOf("DESCRIPTION"))
        every { permissions.fieldPolicy() } returns policy
        val reads = mockk<FeedbackModificationReadPort>()
        every { reads.readModifications(11) } returns listOf(
            fact(1, "description", "旧敏感正文"),
            fact(2, null, "无字段名"),
            fact(3, "module", "B")
        )

        val result = FeedbackModificationQueryService(
            AuthorizedFeedbackModificationSnapshotReader(access, reads)
        ).modifications(11)

        assertEquals(listOf(3L), result.map(FeedbackFieldHistoryResponse::id))
        assertEquals("B", result.single().newValue)
        assertEquals("审核员", result.single().operator)
    }

    @Test
    fun `audit failure prevents external link success`() {
        authorize(setOf("OPERATOR"), "JIRA")
        every { externalReads.source(11) } returns FeedbackExternalSourceFact("JIRA")
        every { externalReads.channel(11, "JIRA") } returns JiraExternalLinkFacts("FP-11")
        every { config.jiraBaseUrl() } returns "https://jira.example"
        every { audits.append(any()) } throws IllegalStateException("AUDIT_DOWN")

        assertThrows(IllegalStateException::class.java) { externalService.externalLink(11) }
    }

    @Test
    fun `detail read snapshot completes before audit transaction is invoked`() {
        val snapshots = mockk<AuthorizedFeedbackDetailSnapshotReader>()
        val assembler = mockk<FeedbackDetailAssembler>()
        val detailAudits = mockk<FeedbackDetailAuditPort>()
        val authorized = AuthorizedFeedbackRead(
            DataAccessScope(setOf("JIRA"), null, null), principal(setOf("OPERATOR")),
            DataFieldPolicy(true, emptySet(), emptySet())
        )
        val snapshot = mockk<FeedbackDetailSnapshot>()
        val source = mockk<FeedbackSourceSnapshot>()
        val response = mockk<FeedbackDetailResponse>()
        every { source.sourceChannel } returns "JIRA"
        every { snapshot.source } returns source
        every { snapshots.read(7) } returns AuthorizedDetailSnapshot(authorized, snapshot, true)
        every { assembler.detail(snapshot, true, authorized.fieldPolicy) } returns response
        every { detailAudits.append(any()) } just Runs

        FeedbackDetailQueryService(snapshots, assembler, detailAudits).detail(7)

        verifyOrder {
            snapshots.read(7)
            assembler.detail(snapshot, true, authorized.fieldPolicy)
            detailAudits.append(FeedbackDetailAuditEvent.DetailViewed(9, 7, "jira"))
        }
    }

    private fun fact(id: Long, field: String?, newValue: String) = FeedbackFieldModificationFact(
        id = id, fieldName = field, oldValue = "A", newValue = newValue, changeType = "MANUAL_CORRECTION",
        changedByType = "USER", userId = 9, mappingVersion = null, normalizationVersion = null,
        changeReason = null, createdAt = LocalDateTime.of(2026, 8, 29, 12, 0), operator = "审核员"
    )

    private fun authorize(roles: Set<String>, channel: String) {
        every { accessRepository.findBySourceId(any()) } answers {
            // 租户维度不在本用例的 scope 桩里（tenants = null），故取值不参与判定。
            FeedbackAccessFacts(firstArg(), channel, "PRODUCTION", "COURSE", "GD", "SZ", "PASS", false, tenant = TenantKeys.DEFAULT)
        }
        every { permissions.scope() } returns DataAccessScope(setOf(channel), null, null)
        every { principals.require() } returns principal(roles)
    }

    private fun principal(roles: Set<String>) = WebPrincipal(
        9, "tester", "hash", "测试用户", roles, setOf("access:phase_one_workspace"), authProvider = "SSO"
    )
}
