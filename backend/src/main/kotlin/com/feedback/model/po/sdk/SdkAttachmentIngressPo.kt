package com.feedback.model.po.sdk

import java.time.LocalDateTime

/** SDK 附件接入阶段的单表持久化对象；安全扫描和正式材料化仍由后续 Worker 推进。 */
data class SdkAttachmentIngressPo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var sourceId: Long? = null,
    var sdkIngestId: Long = 0,
    var attachmentType: String = "",
    var mimeType: String? = null,
    var fileSizeBytes: Long? = null,
    var fileSha256: ByteArray? = null,
    var storagePath: String? = null,
    var storageKeyId: String? = null,
    var clientAttachmentId: String = "",
    var width: Int? = null,
    var height: Int? = null,
    var durationSeconds: Int? = null,
    var annotationData: String? = null,
    var uploadStatus: String = "VERIFIED",
    var integrityStatus: String = "VERIFIED",
    var decryptionStatus: String = "DECRYPTED",
    var malwareScanStatus: String = "PENDING",
    var contentReviewStatus: String = "PENDING",
    var securityStatus: String = "QUARANTINED",
    var sourceKind: String = "SDK",
    var retentionUntil: LocalDateTime = LocalDateTime.now()
)
