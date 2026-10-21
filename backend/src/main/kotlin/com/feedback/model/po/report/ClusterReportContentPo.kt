package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
)

