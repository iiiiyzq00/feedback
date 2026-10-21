package com.feedback.model.po.auth

/** 新建飞书登录短码的写模型；数据库保存摘要，并绑定用户、返回地址和有效期。 */
data class AuthSsoLoginCodePo(
    val codeHash: ByteArray,
    val userId: Long,
    val returnPath: String,
    val ttlSeconds: Long,
)
