package com.feedback.repository.auth

import com.feedback.model.result.auth.FeishuIdentity

/** 尚未消费的 OAuth state 所绑定的站内返回地址和精确回调地址。 */
data class FeishuPendingState(
    val returnPath: String,
    val redirectUri: String,
)

/** 一次性登录码消费后得到的本地用户和原始返回地址。 */
data class FeishuLoginCodeState(
    val userId: Long,
    val returnPath: String,
)

/**
 * 飞书登录一次性凭证与外部身份的数据边界。
 *
 * state 和登录码均只保存摘要；[completeCallback] 自身构成原子本地操作，
 * [consumeLoginCode] 必须由上层登录事务调用，以便与最后登录时间共同提交或回滚。
 */
interface FeishuSsoRepository {
    /** 保存 state 摘要及可信回跳上下文，原始随机值只交给浏览器。 */
    fun createState(stateHash: ByteArray, returnPath: String, redirectUri: String, ttlSeconds: Long)

    /** 读取仍有效、未消费的 state；远程换票前不长期持有数据库行锁。 */
    fun findPendingState(stateHash: ByteArray): FeishuPendingState?

    /** 原子消费 state、绑定外部身份并创建一次性登录码。 */
    fun completeCallback(
        stateHash: ByteArray,
        identity: FeishuIdentity,
        loginCodeHash: ByteArray,
        loginCodeTtlSeconds: Long,
    )

    /** 在调用方事务内锁定并消费一次性登录码，重复或过期消费必须失败。 */
    fun consumeLoginCode(codeHash: ByteArray): FeishuLoginCodeState

    /** 在正式会话建立前更新用户最后登录时间。 */
    fun updateLastLogin(userId: Long)
}
