package com.feedback.model.po.feedback.diagnostics

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class FeedbackDiagnosticAttachmentPo(
    var id: Long = 0, var sourceId: Long = 0, var mimeType: String? = null, var fileSizeBytes: Long? = null,
    var sourceAttachmentId: String? = null, var securityStatus: String? = null,
    var integrityStatus: String? = null, var malwareScanStatus: String? = null,
    var contentReviewStatus: String? = null
)

