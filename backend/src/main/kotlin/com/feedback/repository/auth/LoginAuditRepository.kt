package com.feedback.repository.auth

/**
 * 登录安全事件持久化边界。
 *
 * 每个方法只写入一条审计记录：失败和拦截事件可独立自动提交；成功事件由本地登录服务在
 * “认证事实读取、登录时间更新、成功审计”事务中调用，使成功状态与账号状态共同提交。
 */
interface LoginAuditRepository {
    /** 记录账号或来源 IP 已触发登录限制。 */
    fun recordBlocked(
        username: String,
        clientIp: String,
        accountLimited: Boolean,
        ipLimited: Boolean,
        windowSeconds: Long,
    )

    /** 记录凭据校验失败以及 Redis 固定窗口中的当前次数。 */
    fun recordFailure(
        username: String,
        clientIp: String,
        accountAttempt: Long,
        ipAttempt: Long,
        windowSeconds: Long,
    )

    /** 记录已认证用户的成功登录；调用方决定是否与其他数据库写入共用事务。 */
    fun recordSuccess(userId: Long, username: String, clientIp: String)
}
