package com.feedback.model.po.feedback.diagnostics

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class FeedbackDiagnosticAuditPo(
    val userId: Long,
    val targetId: String,
    val detailJson: String,
    val ipAddress: String?
)

/** 身份集合使用调用方提供的第 201 行哨兵上限，超限由 Repository 失败关闭。 */

