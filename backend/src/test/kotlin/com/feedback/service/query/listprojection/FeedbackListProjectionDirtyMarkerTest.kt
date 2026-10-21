package com.feedback.service.query.listprojection

import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionControlRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class FeedbackListProjectionDirtyMarkerTest {
    @Test
    fun `marker persists before publishing a normalized after commit hint`() {
        val repository = mockk<FeedbackListProjectionDirtyRepository>()
        val publisher = mockk<ApplicationEventPublisher>()
        val control = mockk<FeedbackListProjectionControlRepository>()
        every { control.lockControl() } returns 7
        every { control.incrementLockedEpoch(7) } returns 8
        every { repository.mark(any(), any()) } returns Unit
        every { publisher.publishEvent(any<Any>()) } returns Unit
        val marker = FeedbackListProjectionDirtyMarker(repository, control, publisher)

        marker.mark(FeedbackListProjectionDirtyCommand(listOf(2, 1, 2), FeedbackListProjectionDirtyReason.CONTENT))

        verifyOrder {
            control.lockControl()
            control.incrementLockedEpoch(7)
            repository.mark(listOf(2, 1, 2), FeedbackListProjectionDirtyReason.CONTENT)
        }
        verify(exactly = 1) { publisher.publishEvent(FeedbackListProjectionDirtyCommittedEvent(listOf(1, 2))) }
    }

    @Test
    fun `marker requires the authoritative transaction`() {
        val method = FeedbackListProjectionDirtyMarker::class.java
            .getDeclaredMethod("mark", FeedbackListProjectionDirtyCommand::class.java)
        val transaction = method.getAnnotation(Transactional::class.java)
        assertEquals(Propagation.MANDATORY, transaction.propagation)
    }
}
