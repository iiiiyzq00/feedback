package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOnlineAttachmentPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOnlineAttachmentPo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var sourceId: Long = 0,
    var attachmentType: String = "IMAGE",
    var mimeType: String? = null,
    var fileSizeBytes: Long? = null,
    var fileSha256: ByteArray? = null,
    var storagePath: String? = null,
    var storageKeyId: String? = null,
    var sourceAttachmentId: String? = null,
    var uploadStatus: String = "VERIFIED",
    var integrityStatus: String = "VERIFIED",
    var decryptionStatus: String = "NOT_REQUIRED",
    var securityStatus: String = "QUARANTINED",
    var sourceKind: String = "STATION_FEEDBACK",
    var retentionUntil: LocalDateTime = LocalDateTime.now()
)
