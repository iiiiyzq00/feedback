package com.feedback.model.po.problem.analysis

/** 同 generation 安全列表投影的正文子集；不得由成员投影或原始来源表补齐缺失值。 */
data class ClusterFeedbackProjectionPo(
    var sourceId: Long = 0, var sourceRecordId: String? = null, var title: String? = null,
    var description: String? = null, var diagnosticTypes: String? = null
)
