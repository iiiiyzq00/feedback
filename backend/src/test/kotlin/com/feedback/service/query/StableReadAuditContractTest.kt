package com.feedback.service.query

import com.feedback.service.query.detail.*
import com.feedback.application.port.feedback.detail.*
import com.feedback.security.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 冻结“受保护外链必须先成功写审计才能返回”的契约。 */
class StableReadAuditContractTest {
    private val snapshots = mockk<AuthorizedFeedbackExternalLinkSnapshotReader>()
    private val audits = mockk<FeedbackDetailAuditPort>()
    private val service = FeedbackExternalLinkQueryService(snapshots, audits)

    @Test
    fun `audit failure prevents a validated HTTPS external link from being returned`() {
        every { snapshots.read(7) } returns AuthorizedExternalLinkSnapshot(
            principal = principal(setOf("OPERATOR")), sourceId = 7, channel = "JIRA",
            url = "https://jira.example/browse/FP-7", host = "jira.example"
        )
        every { audits.append(any()) } throws IllegalStateException("AUDIT_UNAVAILABLE")

        assertThrows(IllegalStateException::class.java) { service.externalLink(7) }

        verify(exactly = 1) {
            audits.append(FeedbackDetailAuditEvent.ExternalLinkViewed(9, 7, "JIRA", "jira.example"))
        }
    }

    private fun principal(roles: Set<String>) = WebPrincipal(
        9, "operator", "unused", "操作员", roles,
        setOf("access:phase_one_workspace"), authProvider = "SSO"
    )
}
