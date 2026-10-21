package com.feedback.model.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.applicationidentity.ApplicationAliasBindingStatus
import com.feedback.model.applicationidentity.ApplicationAliasEvidenceType
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.model.applicationidentity.ApplicationIdentityLifecycleStatus
import com.feedback.model.applicationidentity.ApplicationSourceChannel

/** 管理员 identity 列表项；内部生命周期使用强类型。 */
data class ApplicationIdentityListItem(val applicationId: String, val displayName: String, val lifecycleStatus: ApplicationIdentityLifecycleStatus, val mappingVersion: Long)

/** 有界 identity 游标页。 */
data class ApplicationIdentityPage(val items: List<ApplicationIdentityListItem>, val nextCursor: String?)

/** alias 历史项；渠道、类型、状态和证据均为封闭类型。 */
data class ApplicationIdentityAliasItem(val aliasId: String, val sourceChannel: ApplicationSourceChannel, val aliasType: ApplicationAliasType, val normalizedAlias: String, val aliasVersion: Long, val bindingStatus: ApplicationAliasBindingStatus, val applicationId: String, val evidenceType: ApplicationAliasEvidenceType, val requestId: String?)

/** 管理员应用身份诊断结果，不包含来源正文等敏感文本。 */
data class ApplicationIdentityDiagnostics(val totalShadowFacts: Long, val resolvedCount: Long, val unresolvedCount: Long, val ambiguousCount: Long, val activeAliasCounts: List<ApplicationAliasCountItem>, val resolutionRuleVersion: String)

/** 单个 alias 类型的活动数量。 */
data class ApplicationAliasCountItem(val aliasType: ApplicationAliasType, val count: Long)

/** 人工重绑命令；版本、requestId 和原因由 ServiceImpl 再次校验。 */
data class ApplicationAliasRebindCommand(val targetApplicationId: String, val aliasId: String, val expectedAliasVersion: Long, val expectedTargetVersion: Long, val requestId: String, val reason: String)

/** 人工重绑稳定结果；重放时返回首次提交的版本快照。 */
data class ApplicationAliasRebindResult(val requestId: String, val aliasId: String, val aliasVersion: Long, val targetApplicationId: String, val targetMappingVersion: Long, val replayed: Boolean)

/** 人工审核绑定决策的稳定 HTTP 与审计编码。 */
enum class ApplicationReviewedBindingDecision(val code: String) {
    NEW_APPLICATION("NEW_APPLICATION"),
    ALIAS_OF_EXISTING("ALIAS_OF_EXISTING");

    companion object {
        /** HTTP 边界严格解析决策，未知编码不进入业务 Service。 */
        fun fromCode(code: String): ApplicationReviewedBindingDecision = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_REVIEW_DECISION_INVALID")
    }
}

/** reviewed binding 命令；决策在 Controller 边界已经类型化。 */
data class ApplicationReviewedBindingCommand(val candidateDisplayName: String, val expectedSourceCount: Long, val decision: ApplicationReviewedBindingDecision, val targetApplicationId: String?, val expectedTargetVersion: Long?, val requestId: String, val reason: String) {
    /** 兼容内部旧调用方的字符串构造；值仍在对象创建时严格解析为枚举。 */
    constructor(candidateDisplayName: String, expectedSourceCount: Long, decision: String, targetApplicationId: String?, expectedTargetVersion: Long?, requestId: String, reason: String) : this(candidateDisplayName, expectedSourceCount, ApplicationReviewedBindingDecision.fromCode(decision), targetApplicationId, expectedTargetVersion, requestId, reason)
}

/** reviewed binding 结果；decision 仅在 HTTP/审计边界转换稳定 code。 */
data class ApplicationReviewedBindingResult(val requestId: String, val decision: ApplicationReviewedBindingDecision, val applicationId: String, val aliasId: String, val aliasVersion: Long, val targetMappingVersion: Long, val affectedSourceCount: Long, val replayed: Boolean) {
    /** 兼容测试夹具与迁移期内部构造，结果字段仍以强类型保存。 */
    constructor(requestId: String, decision: String, applicationId: String, aliasId: String, aliasVersion: Long, targetMappingVersion: Long, affectedSourceCount: Long, replayed: Boolean) : this(requestId, ApplicationReviewedBindingDecision.fromCode(decision), applicationId, aliasId, aliasVersion, targetMappingVersion, affectedSourceCount, replayed)
}

/** 离线审核准备输入；目标展示名只用于有界定位，不写入 alias。 */
data class ApplicationReviewedBindingPreparation(val candidateDisplayName: String, val expectedSourceCount: Long, val decision: ApplicationReviewedBindingDecision, val targetDisplayName: String?, val reason: String)

/** reviewed binding 预检报告；ready=true 只代表检查时快照满足条件。 */
data class ApplicationReviewedBindingPreflightReport(val decisionCount: Int, val newApplicationCount: Int, val existingApplicationAliasCount: Int, val expectedAffectedSourceCount: Long, val replayableCount: Int, val ready: Boolean)
