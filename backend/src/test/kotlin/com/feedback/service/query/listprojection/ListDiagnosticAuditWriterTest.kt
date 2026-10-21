package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditEvent
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditPort
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ListDiagnosticAuditWriterTest {
    private val audits = mockk<FeedbackDiagnosticsAuditPort>()
    private val writer = ListDiagnosticAuditWriter(audits)

    @Test
    fun `writer persists named direct-list audit facts`() {
        val captured = slot<List<FeedbackDiagnosticsAuditEvent>>()
        every { audits.appendAll(capture(captured)) } just Runs

        writer.append(listOf(ListDiagnosticAudit(7, 11, listOf("DEVICE_SN"), "RAW")))

        val row = captured.captured.single()
        assertEquals(7, row.actorId)
        assertEquals(11, row.sourceId)
        assertEquals("DIRECT_LIST_VIEW", row.purposeCode)
        assertEquals(listOf("DEVICE_SN"), row.types)
    }

    @Test
    fun `audit failure propagates so sensitive list response cannot return`() {
        every { audits.appendAll(any()) } throws ServiceException(
            ErrorCode.INTERNAL_ERROR,
            "FEEDBACK_DIAGNOSTICS_AUDIT_WRITE_FAILED"
        )

        val error = assertThrows(ServiceException::class.java) {
            writer.append(listOf(ListDiagnosticAudit(7, 11, listOf("DEVICE_SN"), "RAW")))
        }

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("FEEDBACK_DIAGNOSTICS_AUDIT_WRITE_FAILED", error.reasonCode)
    }
}
