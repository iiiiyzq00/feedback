package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IndexFeedbackAiPo(
    var sourceId: Long = 0,
    var aiCategory: String? = null,
    var aiSeverity: String? = null,
    var aiSummary: String? = null,
    var aiProcessedAt: LocalDateTime? = null,
    var searchIndexStatus: String? = null,
    var vectorIndexStatus: String? = null,
    var indexMaskingRuleVersion: String? = null
)

