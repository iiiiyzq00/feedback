package com.feedback.mapper.feedback.diagnostics

import com.feedback.model.po.feedback.diagnostics.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface FeedbackDiagnosticAuditMapper {
    fun insertViewAudit(
        @Param("userId") userId: Long, @Param("targetId") targetId: String,
        @Param("detailJson") detailJson: String, @Param("ipAddress") ipAddress: String?
    ): Int
    fun insertViewAudits(@Param("rows") rows: List<FeedbackDiagnosticAuditPo>): Int
}

