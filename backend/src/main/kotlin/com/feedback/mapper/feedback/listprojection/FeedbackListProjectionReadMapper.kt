package com.feedback.mapper.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.*
import com.feedback.model.query.feedback.list.FeedbackListReadCriteria
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface FeedbackListProjectionReadMapper {
    fun findPage(
        @Param("generationId") generationId: Long,
        @Param("criteria") criteria: FeedbackListReadCriteria,
        @Param("limit") limit: Int,
        @Param("offset") offset: Long
    ): List<FeedbackListProjectionPo>

    fun findForExport(
        @Param("generationId") generationId: Long,
        @Param("criteria") criteria: FeedbackListReadCriteria,
        @Param("limit") limit: Int
    ): List<FeedbackListProjectionPo>

    fun findByIds(
        @Param("generationId") generationId: Long,
        @Param("criteria") criteria: FeedbackListReadCriteria,
        @Param("sourceIds") sourceIds: List<Long>
    ): List<FeedbackListProjectionPo>

    fun stats(
        @Param("generationId") generationId: Long,
        @Param("criteria") criteria: FeedbackListReadCriteria
    ): FeedbackListStatsPo
}
