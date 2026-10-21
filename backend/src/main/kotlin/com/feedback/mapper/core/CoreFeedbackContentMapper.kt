package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface CoreFeedbackContentMapper {
    fun findById(@Param("id") id: Long): FeedbackContent?
    fun findByIds(@Param("ids") ids: Collection<Long>): List<FeedbackContent>
    fun upsert(@Param("row") row: FeedbackContent): Int
    fun findByMaskingStatus(@Param("status") status: String): List<FeedbackContent>
    fun findByProcessingStatus(@Param("status") status: String): List<FeedbackContent>
}

