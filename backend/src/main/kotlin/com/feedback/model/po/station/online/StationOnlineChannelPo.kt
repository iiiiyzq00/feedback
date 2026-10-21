package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOnlineChannelPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOnlineChannelPo(
    var sourceId: Long = 0,
    var receiptId: ByteArray = ByteArray(0),
    var sourceApiVersion: String = "V1",
    var productId: String = "",
    var sourceAppId: String? = null,
    var sourceAppName: String? = null,
    var sourceUserId: String? = null,
    var authenticatedUserId: String? = null,
    var authenticatedProductCode: String = "",
    var sourcePlatform: String = "",
    var sourceFeedbackType: String = "",
    var sourceSubChannel: String = "",
    var sourceDescription: String? = null,
    var pendingPhotoUrls: String? = null,
    var sourcePhoneCiphertext: String? = null,
    var contactStatus: String = "NOT_PROCESSED",
    var ingestStatus: String = "RECEIVED",
    var processingStage: String = "RECEIVED",
    var retryCount: Int = 0,
    var maxRetryCount: Int = 5,
    var nextRetryAt: LocalDateTime? = null,
    var leaseOwner: String? = null,
    var leaseExpiresAt: LocalDateTime? = null,
    var lastErrorCode: String? = null,
    var lastErrorSummary: String? = null,
    var requestFingerprint: ByteArray = ByteArray(0),
    var processedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

/** Station 运维任务单表投影；不暴露正文、身份或附件内容。 */
