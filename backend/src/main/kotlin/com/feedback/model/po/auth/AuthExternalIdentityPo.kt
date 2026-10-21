package com.feedback.model.po.auth

/**
 * 外部认证主体与本地用户的持久化绑定。
 * providerSubject（飞书 openId）是登录定位主键，其余字段用于跨应用识别与组织治理。
 */
data class AuthExternalIdentityPo(
    var id: Long? = null,
    val userId: Long,
    val provider: String = "FEISHU",
    val providerSubject: String,
    val unionId: String? = null,
    val employeeId: String? = null,
    val tenantKey: String? = null,
)
