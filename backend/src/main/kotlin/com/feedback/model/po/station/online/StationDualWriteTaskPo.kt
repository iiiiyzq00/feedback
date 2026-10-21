package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationDualWriteTaskPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationDualWriteTaskPo(
    var id: Long = 0,
    var receiptId: ByteArray = ByteArray(0),
    var idempotencyKey: String = "",
    var requestFingerprint: ByteArray = ByteArray(0),
    var apiVersion: String = "V1",
    var payloadCiphertext: ByteArray = ByteArray(0),
    var payloadNonce: ByteArray = ByteArray(0),
    var payloadTag: ByteArray = ByteArray(0),
    var keyId: String = "",
    var oldFbpStatus: String = "PENDING",
    var newPlatformStatus: String = "BLOCKED_BY_OLD",
    var overallStatus: String = "ACCEPTED",
    var retryCount: Int = 0,
    var maxRetryCount: Int = 8,
    var leaseOwner: String? = null
)
