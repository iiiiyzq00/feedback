package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface CoreFeedbackSourceMapper {
    fun findById(@Param("id") id: Long): FeedbackSource?
    fun findByIds(@Param("ids") ids: Collection<Long>): List<FeedbackSource>
    fun insert(@Param("row") row: FeedbackSource): Int
    fun update(@Param("row") row: FeedbackSource): Int
    fun findByChannelRecord(@Param("channel") channel: String, @Param("recordId") recordId: String): FeedbackSource?
    fun findByChannelTime(@Param("channel") channel: String, @Param("start") start: LocalDateTime,
                          @Param("end") end: LocalDateTime): List<FeedbackSource>
    fun countByChannel(@Param("channel") channel: String): Long
    fun findByImportBatchId(@Param("importBatchId") importBatchId: Long): List<FeedbackSource>
}

