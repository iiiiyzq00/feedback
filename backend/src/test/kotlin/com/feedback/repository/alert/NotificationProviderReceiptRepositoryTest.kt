package com.feedback.repository.alert

import com.feedback.mapper.alert.NotificationProviderReceiptAuditMapper
import com.feedback.mapper.alert.NotificationProviderReceiptNotificationMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationProviderReceiptRepositoryTest {
    private val notifications=mockk<NotificationProviderReceiptNotificationMapper>()
    private val audits=mockk<NotificationProviderReceiptAuditMapper>()
    private val repository=NotificationProviderReceiptRepository(notifications,audits)

    @Test fun `notification lookup uses locking mapper statement`() {
        every{notifications.findLatestIdForUpdate("message-1","VOICE_CALL")} returns 9L

        assertTrue(repository.notificationId("message-1","VOICE_CALL")==9L)

        verify{notifications.findLatestIdForUpdate("message-1","VOICE_CALL")}
    }

    @Test fun `latest receipt decision remains inside repository`() {
        every{audits.isLatest(9L,"voice-a","callback-0001")} returnsMany listOf(1,0)

        assertTrue(repository.isLatest(9L,"voice-a","callback-0001"))
        assertFalse(repository.isLatest(9L,"voice-a","callback-0001"))
    }

    @Test fun `provider status updates expose zero row conflicts`() {
        every{notifications.markDelivered(9)} returnsMany listOf(1,0)
        every{notifications.markConnected(9)} returnsMany listOf(1,0)

        assertTrue(repository.markDelivered(9))
        assertFalse(repository.markDelivered(9))
        assertTrue(repository.markConnected(9))
        assertFalse(repository.markConnected(9))
    }
}
