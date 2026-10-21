package com.feedback.dto.auth

import jakarta.validation.constraints.NotBlank

/**
 * 本地账号密码登录请求。
 *
 * 仅用于 HTTP 入站边界，空值在进入认证管理器前由 Bean Validation 拒绝；
 * password 不得写入日志、审计详情或持久化明文字段。
 */
data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)

/**
 * 飞书一次性登录短码兑换请求。
 *
 * code 由平台回调签发而非飞书原始授权码，成功消费后不能重放。
 */
data class FeishuLoginCodeRequest(
    @field:NotBlank val code: String,
)

/**
 * 本地自助注册请求（仅在注册入口打开时有效）。
 *
 * 只接受账号名、展示名和口令三个字段：角色与租户由服务端固定，不接受客户端指定，
 * 避免注册接口变成权限提升入口。password 不得写入日志或审计详情。
 */
data class RegisterRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val displayName: String? = null,
)
