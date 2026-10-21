package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface PromptFeedbackStatsMapper {
    fun stats(@Param("versions") versions: Collection<String>): List<PromptFeedbackStatsPo>
}

