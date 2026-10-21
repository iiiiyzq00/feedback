package com.feedback.mapper.feedback.detail

import com.feedback.model.po.feedback.detail.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface FeedbackDetailSourceMapper {
    fun findById(@Param("sourceId") sourceId: Long): FeedbackDetailSourcePo?
    fun findPreviousCandidates(@Param("cursor") cursor: FeedbackNavigationSourcePo, @Param("channels") channels: Set<String>, @Param("regions") regions: Set<String>?, @Param("limit") limit: Int): List<FeedbackNavigationSourcePo>
    fun findNextCandidates(@Param("cursor") cursor: FeedbackNavigationSourcePo, @Param("channels") channels: Set<String>, @Param("regions") regions: Set<String>?, @Param("limit") limit: Int): List<FeedbackNavigationSourcePo>
}

