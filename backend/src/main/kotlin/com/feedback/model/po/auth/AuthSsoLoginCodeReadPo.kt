package com.feedback.model.po.auth

/** 锁定可消费登录短码后返回的会话建立上下文。 */
data class AuthSsoLoginCodeReadPo(
    val userId: Long,
    val returnPath: String,
)
