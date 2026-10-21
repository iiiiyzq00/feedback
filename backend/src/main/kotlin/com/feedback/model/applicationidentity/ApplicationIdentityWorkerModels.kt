package com.feedback.model.applicationidentity

import com.feedback.model.applicationidentity.ActiveApplicationAlias
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput

/** 应用身份审计动作稳定编码。 */
enum class ApplicationIdentityAuditAction(val code: String) {
    ALIAS_REBIND("APPLICATION_ALIAS_REBIND"), REVIEWED_BINDING("APPLICATION_REVIEWED_BINDING"), SHADOW_BATCH("APPLICATION_IDENTITY_SHADOW_BATCH")
}

/** 应用身份审计分类稳定编码。 */
enum class ApplicationIdentityAuditClass(val code: String) { BUSINESS("BUSINESS") }

/** 应用身份审计结果稳定编码。 */
enum class ApplicationIdentityAuditOutcome(val code: String) { SUCCESS("SUCCESS") }

/** 应用身份审计目标类型稳定编码。 */
enum class ApplicationIdentityAuditTarget(val code: String) { ALIAS("APPLICATION_ALIAS"), SHADOW("APPLICATION_IDENTITY_SHADOW") }

/** 影子解析失败类别的稳定事实表 code。 */
enum class ApplicationResolutionFailure(val code: String) { ALIAS_MISSING("APPLICATION_ALIAS_MISSING"), MULTIPLE_IDENTITIES("APPLICATION_ALIASES_MAP_TO_MULTIPLE_IDENTITIES") }

/** 事务外计算结果与读取快照；写入前必须重新确认 alias/identity 版本未变化。 */
data class ApplicationIdentityShadowPlan(
    val inputs: List<ApplicationIdentitySourceInput>,
    val components: List<List<ApplicationIdentitySourceInput>>,
    val keysBySource: Map<Long, List<ApplicationAliasKey>>,
    val aliasSnapshot: Map<ApplicationAliasKey, ActiveApplicationAlias>,
    val afterSourceId: Long,
    val limit: Int,
    val ruleVersion: String,
    val actor: Long,
    val requestId: String
)
