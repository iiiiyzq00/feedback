package com.feedback.repository.feedback.listprojection

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionActorMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionControlMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionControlPo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackListProjectionControlRepositoryTest {
    private val control = mockk<FeedbackListProjectionControlMapper>()
    private val actor = mockk<FeedbackListProjectionActorMapper>()
    private val repository = FeedbackListProjectionControlRepository(control, actor)

    @Test
    fun `locked epoch advances by affected row CAS before dirty write can continue`() {
        every { control.findForUpdate() } returns FeedbackListProjectionControlPo(mutationEpoch = 41)
        every { control.incrementEpoch(41) } returns 1
        assertEquals(42, repository.incrementLockedEpoch(repository.lockControl()))
        verifyOrder { control.findForUpdate(); control.incrementEpoch(41) }
    }

    @Test
    fun `inactive actor is rejected`() {
        every { actor.isActive(7) } returns false
        assertThrows(ClientException::class.java) { repository.requireActiveActor(7) }
    }

    @Test
    fun `epoch overflow is rejected before mapper CAS`() {
        assertThrows(ServiceException::class.java) { repository.incrementLockedEpoch(Long.MAX_VALUE) }
        io.mockk.verify(exactly = 0) { control.incrementEpoch(any()) }
    }
}
