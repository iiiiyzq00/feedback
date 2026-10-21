package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOnlineRawPayloadPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOnlineRawPayloadPo(
    var sourceId: Long = 0,
    var payloadCiphertext: ByteArray = ByteArray(0),
    var payloadNonce: ByteArray = ByteArray(0),
    var payloadTag: ByteArray = ByteArray(0),
    var keyId: String = "",
    var algorithm: String = "AES-256-GCM",
    var retentionUntil: LocalDateTime = LocalDateTime.now(),
    var deletedAt: LocalDateTime? = null
)
