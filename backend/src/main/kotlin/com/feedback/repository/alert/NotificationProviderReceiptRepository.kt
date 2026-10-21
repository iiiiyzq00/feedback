package com.feedback.repository.alert

import com.feedback.mapper.alert.NotificationProviderReceiptAuditMapper
import com.feedback.mapper.alert.NotificationProviderReceiptNotificationMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class NotificationProviderReceiptRepository(
    private val notifications: NotificationProviderReceiptNotificationMapper,
    private val audits: NotificationProviderReceiptAuditMapper,
) {
    fun notificationId(message: String, channel: String) = notifications.findLatestIdForUpdate(message, channel)
    fun record(callback: String, provider: String, channel: String, message: String, notification: Long?, event: String, digit: String?, time: LocalDateTime, sha: String) =
        audits.insert(callback, provider, channel, message, notification, notification?.toString(), event, digit, time, sha) == 1
    fun isLatest(notification: Long, provider: String, callback: String) = audits.isLatest(notification, provider, callback) == 1
    fun markDelivered(id: Long) = notifications.markDelivered(id) == 1
    fun markConnected(id: Long) = notifications.markConnected(id) == 1
    fun markFailed(id: Long, event: String) = notifications.markFailed(id, event, "供应商异步回执：$event") == 1
    fun receipts(provider: String?, channel: String?, limit: Int) = audits.receipts(provider, channel, limit)
}
