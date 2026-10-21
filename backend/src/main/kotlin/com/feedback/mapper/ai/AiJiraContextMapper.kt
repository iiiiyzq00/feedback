package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AiJiraContextMapper { fun findBySourceId(@Param("sourceId") sourceId: Long): AiJiraContextPo? }

