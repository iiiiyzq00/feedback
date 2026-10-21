package com.feedback.mapper.normalization

import com.feedback.model.po.normalization.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface FaultOccurrenceTimeContentMapper {
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<FaultOccurrenceTimeContentPo>
}

