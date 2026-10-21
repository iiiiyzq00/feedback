package com.feedback.mapper.feedback.detail

import com.feedback.model.po.feedback.detail.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface FeedbackDetailIdentityMapper { fun findTypes(@Param("sourceId") sourceId: Long): List<String> }

