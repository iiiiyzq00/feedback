package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface ClusterCommandLedgerTableMapper {
    fun claim(@Param("row") row: ClusterCommandLedgerPo): Int
    fun findByFactKey(@Param("factKey") factKey: String): ClusterCommandLedgerPo?
    fun complete(@Param("id") id: Long, @Param("snapshot") snapshot: String): Int
    fun insertDecision(@Param("row") row: ClusterDecisionFactPo): Int
}

