package com.feedback.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BusinessTypesTest {
    @Test
    fun `physical channels map to stable logical channels`() {
        assertEquals(LogicalChannel.LEARNING_MACHINE, FeedbackChannel.SDK.logicalChannel)
        assertEquals(LogicalChannel.LEARNING_MACHINE, FeedbackChannel.STATION_FEEDBACK.logicalChannel)
        assertEquals(LogicalChannel.JIRA, FeedbackChannel.JIRA.logicalChannel)
        assertNull(LogicalChannel.fromPhysicalWire("future-channel"))
    }

    @Test
    fun `workflow aliases preserve current boundary behavior`() {
        assertEquals(WorkflowStatus.NEW, WorkflowStatus.fromBoundary("open"))
        assertEquals(WorkflowStatus.PROCESSING, WorkflowStatus.fromBoundary("IN_PROGRESS"))
        assertEquals(WorkflowStatus.CLOSED, WorkflowStatus.fromBoundary("closed"))
        assertNull(WorkflowStatus.fromBoundary("UNKNOWN"))
        assertNull(WorkflowStatus.fromBoundary(" OPEN "))
    }

    @Test
    fun `diagnostic and attachment strings normalize without changing wire values`() {
        assertEquals("APPLICATION_LOG", DiagnosticType.normalizeWire("app_internal_logs"))
        assertEquals("FUTURE_DIAGNOSTIC", DiagnosticType.normalizeWire("future_diagnostic"))
        assertEquals(AttachmentType.ANNOTATED_SCREENSHOT, AttachmentType.fromWire("annotated_screenshot"))
        assertEquals(AttachmentSecurityStatus.VERIFIED, AttachmentSecurityStatus.fromWire("verified"))
        assertNull(AttachmentSecurityStatus.fromStorage("verified"))
        assertEquals("ORIGINAL_LOG_DOWNLOAD", AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD.name)
    }
}
