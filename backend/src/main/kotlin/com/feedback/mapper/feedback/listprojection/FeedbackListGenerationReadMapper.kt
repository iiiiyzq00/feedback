package com.feedback.mapper.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface FeedbackListGenerationReadMapper {
    fun findActive(): List<FeedbackListProjectionGenerationPo>
}

