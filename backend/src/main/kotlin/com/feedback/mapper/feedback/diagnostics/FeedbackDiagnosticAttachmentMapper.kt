package com.feedback.mapper.feedback.diagnostics

import com.feedback.model.po.feedback.diagnostics.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface FeedbackDiagnosticAttachmentMapper {
    fun findLogs(@Param("sourceId") sourceId: Long, @Param("limit") limit: Int): List<FeedbackDiagnosticAttachmentPo>
}
/** 诊断查看安全审计写入边界；调用方要求恰好写入一行。 */

