package com.feedback.model.result.auth

/**
 * 飞书 OAuth 身份的内部稳定模型。
 *
 * 由远程网关从飞书响应转换而来，供登录编排和身份仓储使用；不包含 access token，
 * 也不直接对应数据库表或外部 JSON 契约。
 */
data class FeishuIdentity(
    val openId: String,
    val unionId: String?,
    val employeeId: String?,
    val tenantKey: String?,
    val name: String,
    val avatarUrl: String?,
)
