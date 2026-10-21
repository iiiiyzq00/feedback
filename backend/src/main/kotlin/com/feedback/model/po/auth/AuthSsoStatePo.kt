package com.feedback.model.po.auth

/** 新建 OAuth state 的写模型；只持久化摘要、可信回跳上下文和有效期。 */
data class AuthSsoStatePo(
    val stateHash: ByteArray,
    val returnPath: String,
    val redirectUri: String,
    val ttlSeconds: Long,
)
