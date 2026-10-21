package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AiReviewAiMapper {
    fun findPending(@Param("ids") ids: List<Long>): List<AiReviewAiPo>
    fun complete(@Param("sourceId") sourceId: Long, @Param("status") status: String,
                 @Param("reviewer") reviewer: Long, @Param("comment") comment: String): Int
    fun completeBatch(@Param("ids") ids: List<Long>, @Param("status") status: String,
                      @Param("reviewer") reviewer: Long, @Param("comment") comment: String): Int
}

