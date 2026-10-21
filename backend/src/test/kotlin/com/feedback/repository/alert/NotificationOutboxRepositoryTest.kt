package com.feedback.repository.alert

import com.feedback.mapper.alert.NotificationAttemptMapper
import com.feedback.model.po.alert.NotificationCompletionPo
import com.feedback.mapper.alert.NotificationDeadAuditMapper
import com.feedback.mapper.alert.NotificationOutboxMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationOutboxRepositoryTest {
    private val outbox=mockk<NotificationOutboxMapper>()
    private val attempts=mockk<NotificationAttemptMapper>()
    private val audits=mockk<NotificationDeadAuditMapper>()
    private val repository=NotificationOutboxRepository(outbox,attempts,audits)

    @Test
    fun `delivered rejects stale or concurrently completed outbox row`() {
        every{outbox.delivered(any())} returns 0

        val error=assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.delivered(completion())}

        assertTrue(error.reasonCode=="NOTIFICATION_OUTBOX_STATE_CONFLICT")
    }

    @Test
    fun `retry failure rejects stale or concurrently completed outbox row`() {
        every{outbox.failed(any())} returns 0

        val error=assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.failed(completion())}

        assertTrue(error.reasonCode=="NOTIFICATION_OUTBOX_STATE_CONFLICT")
    }

    @Test
    fun `dead letter audit is not written when terminal update loses state race`() {
        every{outbox.deadLetter(any())} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.deadLetter(completion(),"FEISHU")}

        verify(exactly=0){audits.insert(any(),any(),any(),any())}
    }

    @Test
    fun `dead letter audit failure propagates for terminal update rollback`() {
        every{outbox.deadLetter(any())} returns 1
        every{audits.insert(99,"FEISHU",null,3)} returns 0

        val error=assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.deadLetter(completion(),"FEISHU")}

        assertTrue(error.reasonCode=="NOTIFICATION_DEAD_LETTER_AUDIT_WRITE_FAILED")
    }

    private fun completion()=NotificationCompletionPo(id=99,attempt=3)
}
