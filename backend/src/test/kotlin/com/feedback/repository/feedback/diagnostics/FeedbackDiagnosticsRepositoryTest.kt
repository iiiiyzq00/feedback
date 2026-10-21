package com.feedback.repository.feedback.diagnostics

import com.feedback.application.port.feedback.diagnostics.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.feedback.diagnostics.*
import com.feedback.model.po.feedback.diagnostics.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackDiagnosticsRepositoryTest {
    private val identities = mockk<FeedbackDiagnosticIdentityMapper>()
    private val station = mockk<FeedbackDiagnosticStationMapper>()
    private val sdk = mockk<FeedbackDiagnosticSdkMapper>()
    private val attachments = mockk<FeedbackDiagnosticAttachmentMapper>()
    private val audits = mockk<FeedbackDiagnosticAuditMapper>()
    private val repository = FeedbackDiagnosticsRepository(identities, station, sdk, attachments, audits)

    @Test fun `the two hundred first identity row is a fail closed sentinel`() {
        every { identities.findAuthorized(1, 201) } returns (1L..201L).map { FeedbackDiagnosticIdentityPo(id = it) }
        val error = assertThrows(ServiceException::class.java) { repository.identities(1) }
        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_IDENTITY_LIMIT_EXCEEDED", error.reasonCode)
    }

    @Test fun `duplicate sdk rows fail closed`() {
        every { sdk.findBySourceId(1, 201) } returns listOf(
            FeedbackDiagnosticSdkPo(sourceId = 1), FeedbackDiagnosticSdkPo(sourceId = 1)
        )
        val error = assertThrows(ServiceException::class.java) { repository.sdk(1) }
        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_SDK_DUPLICATE", error.reasonCode)
    }

    @Test fun `audit must affect exactly one row`() {
        every { audits.insertViewAudit(7, "1", "{}", null) } returns 0
        val error = assertThrows(ServiceException::class.java) { repository.appendAudit(7, 1, "{}", null) }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_AUDIT_WRITE_FAILED", error.reasonCode)
    }

    @Test fun `invalid batch uses the shared client exception contract`() {
        val error = assertThrows(ClientException::class.java) { repository.identitiesBySourceIds(emptyList()) }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_BATCH_INVALID", error.reasonCode)
    }
}
