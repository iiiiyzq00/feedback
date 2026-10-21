package com.feedback.model.po.attachment

/** 已发布私有派生预览的位置 MyBatis 投影；只允许仓储实现转换后交给内容提供器。 */
data class AttachmentMediaPreviewLocationPo(
    var id: Long = 0,
    var objectKey: String? = null,
    var objectVersion: String? = null,
    var objectEtag: String? = null,
    var contentLength: Long? = null,
    var width: Int? = null,
    var height: Int? = null,
    var status: String? = null,
)
