package com.feedback.model.po.auth

import com.feedback.model.auth.FeishuMappingDecision
import java.time.LocalDateTime

/** 飞书授权映射表持久化对象；version 用于草稿编辑与审批状态迁移 CAS。 */
data class AuthFeishuRoleMappingPo(
    var id: Long? = null,
    val ruleType: String,
    val subjectId: String,
    val subjectName: String,
    val grantKind: String,
    val roleCode: String?,
    val scopeType: String?,
    val scopeValue: String?,
    val priority: Int,
    val decisionStatus: String = FeishuMappingDecision.DRAFT.code,
    val description: String?,
    val decisionReason: String? = null,
    val version: Long = 1,
    val createdBy: Long,
    val decidedBy: Long? = null,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null,
    val decidedAt: LocalDateTime? = null,
)
