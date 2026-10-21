package com.feedback.model.po.problem.analysis

import java.time.LocalDateTime

/** 成员投影单表聚合出的簇级范围事实，不包含 `t_cluster` 生命周期字段。 */
data class ProblemClusterPo(
    var clusterId: Long = 0, var recordCount: Long = 0, var logicalChannelCount: Long = 0,
    var firstOccurredAt: LocalDateTime? = null, var lastOccurredAt: LocalDateTime? = null,
    var attachmentCount: Long = 0, var screenshotCount: Long = 0, var logCount: Long = 0, var videoCount: Long = 0,
    var stationCount: Long = 0, var jiraCount: Long = 0, var qualityCount: Long = 0, var redblackCount: Long = 0
)
