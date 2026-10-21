package com.feedback.mapper.feedback.diagnostics

import com.feedback.model.po.feedback.diagnostics.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface FeedbackDiagnosticIdentityMapper {
    fun findAuthorized(@Param("sourceId") sourceId: Long, @Param("limit") limit: Int): List<FeedbackDiagnosticIdentityPo>
    fun findAuthorizedBySourceIds(@Param("sourceIds") sourceIds: List<Long>, @Param("perSourceLimit") perSourceLimit: Int): List<FeedbackDiagnosticIdentityPo>
}
/** Station 事实预期至多一行；较宽的哨兵 limit 允许 Repository 检出重复数据而不是静默选一行。 */

