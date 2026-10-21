package com.feedback.model.po.applicationidentity

/** masking PASS 且未删除的 t_feedback_content 安全文本投影。 */
data class ApplicationIdentitySafeTextPo(
    val sourceId: Long,
    val title: String?,
    val description: String?
)
