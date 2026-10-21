package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface KnowledgeClosureAuditMapper {
    fun countAdoption(@Param("targetId") targetId: String, @Param("correlationId") correlationId: String): Int
    fun insertUsage(@Param("row") row: KnowledgeUsageAuditInsertPo): Int
    fun aggregate(@Param("targetId") targetId: String): KnowledgeUsageAggregatePo
}

