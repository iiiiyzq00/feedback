package com.feedback.model.po.feedback.listprojection

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FeedbackListStatsPo(
    var total: Long = 0,
    var latestTime: LocalDateTime? = null,
    var latestUpdateTime: LocalDateTime? = null,
    var latestIngestTime: LocalDateTime? = null,
    var channelCount: Long = 0,
    var unassignedCount: Long = 0,
    var activeCount: Long = 0,
    var resolvedCount: Long = 0,
    var withEvidenceCount: Long = 0,
    var positiveCount: Long = 0,
    var negativeCount: Long = 0,
    var newRecords5m: Long = 0
)

/** 已由应用层规范化的数据范围与筛选条件；Mapper 只在安全投影单表内执行。 */

