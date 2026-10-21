package com.feedback.model.auth

/** 飞书授权策略预览输入；组织 ID 是外部可扩展值，不使用封闭枚举。 */
data class FeishuPolicyPreviewInput(
    val openId: String? = null,
    val groupIds: Set<String> = emptySet(),
    val departmentIds: Set<String> = emptySet(),
)

/** 飞书授权映射 HTTP 输入；封闭编码在 ServiceImpl 入口立即转换为领域类型。 */
data class FeishuRoleMappingInput(
    val ruleType: String,
    val subjectId: String,
    val subjectName: String,
    val roleCode: String,
    val priority: Int = 100,
    val description: String? = null,
    val expectedVersion: Long? = null,
    val grantKind: String = FeishuGrantKind.ROLE.code,
    val scopeType: String? = null,
    val scopeValue: String? = null
)

/** 飞书授权映射审核输入；版本用于 CAS 防止覆盖并发审核。 */
data class FeishuRoleMappingDecisionInput(
    val status: String,
    val reason: String,
    val expectedVersion: Long
)

/** 前端下一步应跳转的飞书授权地址。 */
data class FeishuAuthorizeResult(val url: String)

/** 飞书回调处理完毕后，服务端要求浏览器跳转的前端地址。 */
data class FeishuCallbackResult(val redirectUrl: String)

/** 一次性短码兑换结果，用于建立会话并恢复授权前页面。 */
data class FeishuLoginResult(
    val userId: Long,
    /** 仅供服务端创建 Redis 会话，不进入 HTTP 响应。 */
    val authVersion: Long,
    val user: Map<String, Any?>,
    val returnPath: String,
)

/** 本地账号认证成功后供控制器签发会话和生成响应使用的内部快照。 */
data class AuthenticatedUserResult(
    val userId: Long,
    val username: String,
    val displayName: String?,
    val roles: Set<RoleCode>,
    val permissions: Set<PermissionCode>,
    val authVersion: Long,
    val authProvider: AuthProvider,
)

/** 授权策略所需的最小飞书组织事实；不包含姓名、邮箱等无关个人信息。 */
data class FeishuOrganizationFacts(
    val groupIds: Set<String>,
    val departmentIds: Set<String>,
    val active: Boolean
)
