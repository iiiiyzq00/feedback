package com.feedback.model.po.cluster

/** VERIFIED shadow 预览专用的最小安全证据行；不包含来源业务标识或原始身份字段。 */
data class ClusterShadowPreviewEvidencePo(
    var sourceId: Long = 0,
    var sourceChannel: String = "",
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
    var maskingStatus: String = "PENDING",
    var isDeleted: Boolean = false,
)
