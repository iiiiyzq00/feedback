package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IndexClusterPo(
    var id: Long = 0,
    var clusterName: String = "",
    var canonicalSymptom: String? = null,
    var functionModule: String? = null,
    var issueCategory: String? = null,
    var clusterStatus: String = "ACTIVE",
    var searchIndexStatus: String? = null,
    var vectorIndexStatus: String? = null,
    var indexMaskingRuleVersion: String? = null,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

