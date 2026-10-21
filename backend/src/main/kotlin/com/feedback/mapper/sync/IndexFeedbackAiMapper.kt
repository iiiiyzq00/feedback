package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexFeedbackAiMapper {
    fun countPending(): Long
    fun findAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<IndexFeedbackAiPo>
    fun findById(@Param("id") id: Long): IndexFeedbackAiPo?
    fun markSearchSynced(@Param("id") id: Long, @Param("searchId") searchId: String): Int
    fun markVectorSynced(@Param("id") id: Long, @Param("vectorId") vectorId: String, @Param("model") model: String,
                         @Param("ruleVersion") ruleVersion: String, @Param("dimension") dimension: Int): Int
    fun markVectorUnavailable(@Param("id") id: Long, @Param("ruleVersion") ruleVersion: String): Int
    fun markDeleted(@Param("id") id: Long, @Param("vectorStatus") vectorStatus: String): Int
    fun markPending(@Param("id") id: Long): Int
}

