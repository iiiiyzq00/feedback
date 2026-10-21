package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class ReportIdempotencyInsertPo(
    var key: String = "",
    var source: String = "",
    var baseKey: String = "",
    var regeneration: Int = 0,
)

