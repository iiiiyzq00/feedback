package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportKnowledgePo(
    var id: Long = 0,
    var title: String? = null,
    var content: String? = null,
    var source: String? = null,
    var status: String? = null,
    var adoptionCount: Long = 0,
    var createdAt: LocalDateTime? = null,
)

