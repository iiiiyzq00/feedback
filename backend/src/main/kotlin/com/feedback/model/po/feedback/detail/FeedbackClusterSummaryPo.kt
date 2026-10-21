package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackClusterSummaryPo(var id: Long = 0, var clusterName: String? = null, var totalRecordCount: Long? = null)

/** 导航 keyset 候选；正文可见性与模块范围需要后续批量读取 content 事实才能判定。 */

