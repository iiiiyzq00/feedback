package com.feedback.mapper.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface FeedbackListOwnerFactsMapper { fun findByIds(@Param("ids") ids: List<Long>): List<FeedbackListOwnerFactsPo> }

