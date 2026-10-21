package com.feedback.model.po.auth

/** 锁定可用 OAuth state 后返回的最小回调上下文，不包含摘要和数据库状态字段。 */
data class AuthSsoStateReadPo(
    val returnPath: String,
    val redirectUri: String,
)
