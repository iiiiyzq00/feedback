package com.feedback.model.po.attachment

import java.time.LocalDateTime

/**
 * 反馈附件列表的单表持久化投影。
 *
 * 存储路径只在 Mapper 内转换为是否存在内容的布尔事实，避免路径进入 Repository、Service 或公开响应。
 * `queryTime` 取自与附件行相同的数据库查询，供上层以同一时钟判断保留期；缺失时不得生成可用性结论。
 */
data class FeedbackAttachmentListPo(
    var id: Long = 0,
    var sourceId: Long = 0,
    var attachmentType: String = "",
    var mimeType: String? = null,
    var fileSizeBytes: Long? = null,
    var width: Int? = null,
    var height: Int? = null,
    var durationSeconds: Int? = null,
    var uploadStatus: String? = null,
    var securityStatus: String? = null,
    var integrityStatus: String? = null,
    var decryptionStatus: String? = null,
    var malwareScanStatus: String? = null,
    var contentReviewStatus: String? = null,
    var sourceKind: String? = null,
    var retentionUntil: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null,
    var deletedAt: LocalDateTime? = null,
    var storedContentPresent: Boolean = false,
    var mediaPreviewStatus: String? = null,
    var queryTime: LocalDateTime? = null
)
