package com.feedback.model.po.applicationidentity



/** t_feedback_primary_problem_fact 持久化行；状态字符串在 RepositoryImpl 转换。 */
data class PrimaryProblemFactPo(
    val feedbackSourceId: Long,
    val applicationIdentityId: Long?,
    val applicationPublicIdSnapshot: String?,
    val applicationDisplayNameSnapshot: String?,
    val applicationMappingVersion: Long?,
    val applicationResolutionStatus: String,
    val classificationStatus: String,
    val resolutionRuleVersion: String,
    val resolutionReasonCode: String?,
    val resolvedAliasCount: Int
)
