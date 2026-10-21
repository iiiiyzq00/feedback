package com.feedback.service.alert

import com.feedback.model.po.alert.NotificationAttemptPo
import com.feedback.model.po.alert.NotificationCompletionPo
import com.feedback.model.po.alert.NotificationOutboxPo
import com.feedback.repository.alert.NotificationOutboxRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

class NotificationOutboxTest {
    private val repository=mockk<NotificationOutboxRepository>()
    private val outbox=NotificationOutbox(repository)

    @Test fun `retryable failure persists provider evidence and schedules retry`() {
        every{repository.nextAttempt(99)} returns 3;val attempt=slot<NotificationAttemptPo>();every{repository.record(capture(attempt))} just Runs
        val state=slot<NotificationCompletionPo>();every{repository.failed(capture(state))} just Runs
        outbox.complete(row(retry=1,max=5),DeliveryResult.failure("HTTP_5XX","HTTP 503",503,"UPSTREAM_BUSY"),27)
        assertEquals(503,attempt.captured.httpStatus);assertEquals("UPSTREAM_BUSY",attempt.captured.providerCode);assertEquals(2,state.captured.attempt);assertEquals(4L,state.captured.delaySeconds)
    }

    @Test fun `exhausted failure becomes dead letter and emits audit through repository`() {
        every{repository.nextAttempt(99)} returns 1;every{repository.record(any())} just Runs;val state=slot<NotificationCompletionPo>();every{repository.deadLetter(capture(state),"FEISHU")} just Runs
        outbox.complete(row(retry=4,max=5),DeliveryResult.failure("DNS_FAILURE","DNS解析失败"),9)
        assertEquals(5,state.captured.attempt)
    }

    @Test fun `success becomes delivered and persists HTTP and provider fields`() {
        every{repository.nextAttempt(99)} returns 2;val attempt=slot<NotificationAttemptPo>();every{repository.record(capture(attempt))} just Runs
        val state=slot<NotificationCompletionPo>();every{repository.delivered(capture(state))} just Runs
        outbox.complete(row(),DeliveryResult.success(200,"0","provider-42"),15)
        assertEquals(2,attempt.captured.attempt);assertEquals(200,state.captured.httpStatus);assertEquals("provider-42",state.captured.providerMessageId)
    }

    @Test fun `replayed cycle keeps globally increasing attempt number`() {
        every{repository.nextAttempt(99)} returns 8;val attempt=slot<NotificationAttemptPo>();every{repository.record(capture(attempt))} just Runs;every{repository.delivered(any())} just Runs
        outbox.complete(row(),DeliveryResult.success(200,"0"),11);assertEquals(8,attempt.captured.attempt)
    }

    @Test fun `terminal state conflict propagates to transaction boundary`() {
        every{repository.nextAttempt(99)} returns 1
        every{repository.record(any())} just Runs
        every{repository.delivered(any())} throws IllegalStateException("NOTIFICATION_OUTBOX_STATE_CONFLICT:99:DELIVERED")

        val error=assertThrows(IllegalStateException::class.java){outbox.complete(row(),DeliveryResult.success(200,"0"),11)}

        assertTrue(error.message.orEmpty().contains("NOTIFICATION_OUTBOX_STATE_CONFLICT"))
        verifyOrder { repository.nextAttempt(99);repository.record(any());repository.delivered(any()) }
    }

    @Test fun `completion entry point remains one transaction boundary`() {
        val method=NotificationOutbox::class.java.getDeclaredMethod("complete",NotificationOutboxPo::class.java,DeliveryResult::class.java,Long::class.javaPrimitiveType)
        assertTrue(method.isAnnotationPresent(Transactional::class.java))
    }

    private fun row(retry:Int=0,max:Int=5)=NotificationOutboxPo(99,"FEISHU",retryCount=retry,maxRetries=max)
}
