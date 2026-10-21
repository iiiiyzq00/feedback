package com.feedback.model.po.problem.analysis

/** 按来源语义时间和 source ID 稳定选出的代表反馈键。 */
data class ClusterRepresentativePo(var clusterId: Long = 0, var sourceId: Long = 0)
