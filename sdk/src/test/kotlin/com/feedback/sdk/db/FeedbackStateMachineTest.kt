package com.feedback.sdk.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedbackStateMachineTest {
    @Test
    fun `body ack advances to attachments`() {
        assertEquals(
            "PENDING_ATTACHMENTS",
            FeedbackStateMachine.requireTransition("PENDING_BODY", FeedbackStatus.PENDING_ATTACHMENTS)
        )
    }

    @Test
    fun `body without attachments may complete`() {
        assertEquals(
            "COMPLETED",
            FeedbackStateMachine.requireTransition("PENDING_BODY", FeedbackStatus.COMPLETED)
        )
    }

    @Test
    fun `terminal states cannot transition`() {
        assertThrows(IllegalStateException::class.java) {
            FeedbackStateMachine.requireTransition("COMPLETED", FeedbackStatus.PENDING_BODY)
        }
        assertThrows(IllegalStateException::class.java) {
            FeedbackStateMachine.requireTransition("PERMANENT_FAILED", FeedbackStatus.COMPLETED)
        }
    }

    @Test
    fun `retry exhausted body can be explicitly requeued`() {
        assertEquals(
            "PENDING_BODY",
            FeedbackStateMachine.requireTransition("RETRY_EXHAUSTED", FeedbackStatus.PENDING_BODY)
        )
    }
}
