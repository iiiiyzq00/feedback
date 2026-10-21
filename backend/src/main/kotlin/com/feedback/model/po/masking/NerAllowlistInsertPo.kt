package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class NerAllowlistInsertPo(
    var id: Long = 0, val term: String, val type: String, val version: String,
    val reason: String, val actor: Long
)

