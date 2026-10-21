package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface FeedbackAiMapper {
    fun findById(@Param("sourceId") sourceId: Long): FeedbackAi?
    fun upsert(@Param("row") row: FeedbackAi): Int
    fun findTopBySearchStatus(@Param("status") status: String, @Param("limit") limit: Int): List<FeedbackAi>
    fun findTopByVectorStatus(@Param("status") status: String, @Param("limit") limit: Int): List<FeedbackAi>
}

