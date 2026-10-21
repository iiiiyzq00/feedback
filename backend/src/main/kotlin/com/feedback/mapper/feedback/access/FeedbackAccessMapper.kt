package com.feedback.mapper.feedback.access

import com.feedback.model.result.feedback.FeedbackAccessResult
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface FeedbackAccessMapper {
    fun findBySourceId(@Param("sourceId") sourceId: Long): FeedbackAccessResult?
}
