package com.feedback.service.query

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.repository.feedback.access.FeedbackAccessFacts
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticIdentityFact
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticStationFact
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditEvent
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditPort
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsReadPort
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.TenantKeys
import com.feedback.security.FeedbackAccessContext
import com.feedback.security.FeedbackAccessGuard
import com.feedback.security.WebPrincipal
import com.feedback.service.masking.ContactStorageCrypto
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackDiagnosticsApplicationServiceTest {
    private val accessGuard = mockk<FeedbackAccessGuard>()
    private val reads = mockk<FeedbackDiagnosticsReadPort>()
    private val audits = mockk<FeedbackDiagnosticsAuditPort>()
    private val principals = mockk<CurrentWebPrincipalProvider>()
    private val crypto = mockk<ContactStorageCrypto>()
    private val objectMapper = jacksonObjectMapper()
    private val service = FeedbackDiagnosticsApplicationService(
        accessGuard, reads, audits, principals, FeedbackDiagnosticValueDecoder(crypto, objectMapper)
    )
    private val material = byteArrayOf(1)

    @Test fun `product role receives locally hidden value and successful audit`() {
        allowAccess("SDK")
        val audit = slot<FeedbackDiagnosticsAuditEvent>()
        every { principals.require() } returns principal(setOf("access:phase_one_workspace"))
        every { crypto.keyId } returns "contact-storage-v1"
        every { crypto.decrypt(material, material, material) } returns "13812345678"
        every { reads.identities(1) } returns listOf(identity())
        every { reads.sdk(1) } returns null
        every { reads.attachments(1) } returns emptyList()
        every { audits.append(capture(audit)) } just runs

        val result = service.viewDirect(1, "127.0.0.1")

        assertEquals("138****5678", result.diagnostics.single().value)
        assertEquals("PARTIALLY_HIDDEN", result.contentVisibility)
        assertEquals("DIRECT_VIEW", audit.captured.purposeCode)
        assertEquals("反馈直接查看".length, audit.captured.purposeLength)
        assertEquals(64, audit.captured.purposeSha256.length)
        assertEquals("127.0.0.1", audit.captured.ipAddress)
        verify(exactly = 1) { audits.append(any()) }
    }

    @Test fun `audit failure prevents diagnostics response`() {
        allowAccess("SDK")
        every { principals.require() } returns principal(setOf("access:phase_one_workspace", "access:phase_one_raw_content"))
        every { crypto.keyId } returns "contact-storage-v1"
        every { crypto.decrypt(material, material, material) } returns "raw-secret"
        every { reads.identities(1) } returns listOf(identity())
        every { reads.sdk(1) } returns null
        every { reads.attachments(1) } returns emptyList()
        every { audits.append(any()) } throws IllegalStateException("audit failed")

        assertThrows(IllegalStateException::class.java) { service.viewDirect(1, null) }
    }

    @Test fun `unavailable key fails before audit`() {
        allowAccess("JIRA")
        every { principals.require() } returns principal(setOf("access:phase_one_workspace"))
        every { crypto.keyId } returns "current-key"
        every { reads.identities(1) } returns listOf(identity().copy(keyId = "old-key"))

        val error = assertThrows(ServiceException::class.java) { service.viewDirect(1, null) }
        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_KEY_UNAVAILABLE", error.reasonCode)
        verify(exactly = 0) { audits.append(any()) }
    }

    @Test fun `same user identity from vault and station snapshot is returned once`() {
        allowAccess("STATION_FEEDBACK")
        every { principals.require() } returns principal(setOf("access:phase_one_workspace", "access:phase_one_raw_content"))
        every { crypto.keyId } returns "contact-storage-v1"
        every { crypto.decrypt(material, material, material) } returns "station-user-7"
        every { reads.identities(1) } returns listOf(identity().copy(identityType = "USER_ID"))
        every { reads.station(1) } returns FeedbackDiagnosticStationFact(1, "station-user-7", null)
        every { reads.attachments(1) } returns emptyList()
        every { audits.append(any()) } just runs

        val result = service.viewDirect(1, null)

        assertEquals(1, result.diagnostics.size)
        assertEquals("USER_ID", result.diagnostics.single().type)
        assertEquals("station-user-7", result.diagnostics.single().value)
        assertEquals("ENCRYPTED_VAULT", result.diagnostics.single().source)
    }

    private fun identity() = FeedbackDiagnosticIdentityFact(
        sourceId = 1, identityType = "PHONE", ciphertext = material, nonce = material,
        tag = material, keyId = "contact-storage-v1", algorithm = "AES-256-GCM"
    )

    private fun allowAccess(channel: String) {
        every { accessGuard.require(1) } returns FeedbackAccessContext(
            // 本用例的 scope 桩不带租户维度（tenants = null），租户值不被判定消费；
            // 取迁移期默认租户，表示「这一条不是本用例要验的东西」。
            FeedbackAccessFacts(1, channel, "PRODUCTION", null, null, null, "PASS", false, tenant = TenantKeys.DEFAULT),
            DataAccessScope(setOf(channel), null, null)
        )
    }
    private fun principal(permissions: Set<String>) = WebPrincipal(
        7, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"), permissions, authProvider = "SSO"
    )
}
