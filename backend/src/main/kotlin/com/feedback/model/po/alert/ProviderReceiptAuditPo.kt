package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProviderReceiptAuditPo(
    var id: Long = 0,
    var callbackId: String = "",
    var providerCode: String = "",
    var channel: String = "",
    var providerMessageId: String = "",
    var notificationId: Long? = null,
    var eventType: String = "",
    var dtmfDigit: String? = null,
    var eventTime: LocalDateTime = LocalDateTime.MIN,
    var payloadSha256: String = "",
    var receivedAt: LocalDateTime = LocalDateTime.MIN,
)

