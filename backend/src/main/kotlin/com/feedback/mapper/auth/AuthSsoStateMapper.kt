package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthSsoStatePo
import com.feedback.model.po.auth.AuthSsoStateReadPo
import org.apache.ibatis.annotations.Mapper

/**
 * OAuth state 表 Mapper。
 *
 * 提供无锁有效性检查、事务内行锁复核与条件消费；只有 [lockUsable] 必须在事务中调用，
 * [findUsable] 用于远程 OAuth 交换前读取上下文，避免在网络 I/O 期间占用数据库锁。
 */
@Mapper
interface AuthSsoStateMapper {
    /** 写入 state 摘要、可信回跳上下文和有效期。 */
    fun insert(state: AuthSsoStatePo): Int

    /** 无锁读取仍有效且未消费的 state，供远程调用前做第一阶段校验。 */
    fun findUsable(stateHash: ByteArray): AuthSsoStateReadPo?

    /** 使用 FOR UPDATE 重新验证并锁定 state，必须与 [consume] 位于同一事务。 */
    fun lockUsable(stateHash: ByteArray): AuthSsoStateReadPo?

    /** 仅在尚未消费时写入消费时间，返回 0 表示并发请求已经抢先完成消费。 */
    fun consume(stateHash: ByteArray): Int
}
