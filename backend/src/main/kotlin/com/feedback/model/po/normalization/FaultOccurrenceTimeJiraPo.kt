package com.feedback.model.po.normalization

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FaultOccurrenceTimeJiraPo(var sourceId: Long = 0, var jiraCreatedAt: LocalDateTime? = null)

