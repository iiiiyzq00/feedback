package com.feedback.mapper.feedback.diagnostics

import com.feedback.model.po.feedback.diagnostics.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface FeedbackDiagnosticSdkMapper {
    fun findBySourceId(@Param("sourceId") sourceId: Long, @Param("limit") limit: Int): List<FeedbackDiagnosticSdkPo>
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>, @Param("perSourceLimit") perSourceLimit: Int): List<FeedbackDiagnosticSdkPo>
}
/** 日志附件集合使用第 201 行哨兵，正文只能经独立一次性安全流读取。 */

