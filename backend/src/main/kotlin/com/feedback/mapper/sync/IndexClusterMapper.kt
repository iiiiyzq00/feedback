package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexClusterMapper {
    fun countPending(): Long
    fun findAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<IndexClusterPo>
    fun findById(@Param("id") id: Long): IndexClusterPo?
    fun markSearchSynced(@Param("id") id: Long): Int
    fun markVectorSynced(@Param("id") id: Long, @Param("ruleVersion") ruleVersion: String): Int
    fun markVectorUnavailable(@Param("id") id: Long, @Param("ruleVersion") ruleVersion: String): Int
    fun markDeleted(@Param("id") id: Long, @Param("vectorStatus") vectorStatus: String): Int
}

