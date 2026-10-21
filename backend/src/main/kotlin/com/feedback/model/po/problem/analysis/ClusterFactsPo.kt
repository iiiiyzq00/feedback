package com.feedback.model.po.problem.analysis

/** `t_cluster` 的生命周期与展示事实；不承载当前用户范围内的统计。 */
data class ClusterFactsPo(
    var id: Long = 0, var name: String = "", var canonicalSymptom: String? = null,
    var functionModule: String? = null, var issueCategory: String? = null, var status: String = "",
    var lifecycleVersion: Long = 0, var mergedIntoClusterId: Long? = null,
    var splitFromClusterId: Long? = null, var statusReason: String? = null
)
