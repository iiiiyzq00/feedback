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

@Mapper interface FeedbackClusterMemberProjectionMapper {
    fun upsertAll(@Param("rows") rows: List<FeedbackClusterMemberProjectionPo>): Int
    fun deleteBySourceIds(@Param("generationId") generationId: Long, @Param("sourceIds") sourceIds: List<Long>): Int
    fun findAfterSourceId(@Param("generationId") generationId: Long, @Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<FeedbackClusterMemberProjectionPo>
}

