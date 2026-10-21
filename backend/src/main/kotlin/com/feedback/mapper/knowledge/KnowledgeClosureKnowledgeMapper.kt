package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface KnowledgeClosureKnowledgeMapper {
    fun countPublished(@Param("id") id: Long): Int
    fun findStatistics(@Param("id") id: Long): KnowledgeUsageStatisticsPo?
    fun updateStatistics(@Param("id") id: Long, @Param("aggregate") aggregate: KnowledgeUsageAggregatePo,
                         @Param("effectivenessScore") effectivenessScore: BigDecimal?): Int
}

