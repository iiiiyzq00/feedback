package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacyChannelPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacyChannelPo(
    var sourceId: Long = 0,
    var receiptId: ByteArray = ByteArray(0),
    var sourceApiVersion: String = "V2",
    var productId: String = "UNKNOWN",
    var sourceProductName: String? = null,
    var sourceAppId: String? = null,
    var sourceAppName: String? = null,
    var sourceUserId: String? = null,
    var authenticatedUserId: String? = null,
    var authenticatedProductCode: String = "UNKNOWN",
    var sourcePlatform: String = "UNKNOWN",
    var sourceFeedbackType: String = "UNKNOWN",
    var sourceSubChannel: String = "UNKNOWN",
    var sourceDescription: String? = null,
    var pendingPhotoUrls: String? = null,
    var contactStatus: String = "NOT_PROVIDED",
    var ingestStatus: String = "COMPLETED",
    var processingStage: String = "COMPLETED",
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
