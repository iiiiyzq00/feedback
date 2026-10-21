package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportClusterPo(
    var id: Long = 0,
    var name: String = "",
    var canonicalSymptom: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
    var status: String? = null,
    var lifecycleVersion: Long = 0,
    var mergedIntoClusterId: Long? = null,
    var splitFromClusterId: Long? = null,
    var statusReason: String? = null,
)

