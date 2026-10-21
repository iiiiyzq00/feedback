package com.feedback.service.auth

/** 本地密码已验证后的短写事务边界。 */
interface LocalLoginSuccessService {
    /** 条件确认认证快照，并原子写入登录时间和成功审计。 */
    fun commit(userId: Long, username: String, expectedAuthVersion: Long, clientIp: String)
}
