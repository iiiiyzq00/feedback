package com.feedback.mapper.normalization

import com.feedback.model.po.normalization.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface FaultOccurrenceTimeSourceMapper {
    fun findPendingAfter(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<FaultOccurrenceTimeSourcePo>
    fun updateAll(@Param("updates") updates: List<FaultOccurrenceTimeUpdatePo>): Int
}

