package com.feedback.repository.auth

/**
 * 用户登录时间与会话版本的数据边界。
 *
 * 每个能力都由一条原子 UPDATE 完成；是否加入事务由调用阶段的其他数据库交互决定。
 */
interface AuthSessionRepository {
    /**
     * 仅在本地账号仍启用且 authVersion 与验密前快照一致时记录登录时间。
     * 返回 false 表示验密期间账号或授权状态发生变化，调用方不得继续签发会话。
     */
    fun recordSuccessfulLocalLogin(userId: Long, expectedAuthVersion: Long): Boolean

    /** 递增 authVersion，使此前签发的全部服务端会话失效。 */
    fun invalidateAllSessions(userId: Long)
}
