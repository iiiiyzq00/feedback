package com.feedback.mapper.feedback.diagnostics

import com.feedback.model.po.feedback.diagnostics.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface FeedbackDiagnosticStationMapper {
    fun findBySourceId(@Param("sourceId") sourceId: Long, @Param("limit") limit: Int): List<FeedbackDiagnosticStationPo>
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>, @Param("perSourceLimit") perSourceLimit: Int): List<FeedbackDiagnosticStationPo>
}
/** SDK 事实预期至多一行；较宽的哨兵 limit 允许 Repository 检出重复数据而不是静默选一行。 */

