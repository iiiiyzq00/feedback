package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthSsoLoginCodePo
import com.feedback.model.po.auth.AuthSsoLoginCodeReadPo
import org.apache.ibatis.annotations.Mapper

/**
 * 飞书一次性登录短码表 Mapper。
 *
 * 数据库只保存短码摘要；[lockUsable] 与 [consume] 必须由同一上层事务调用，
 * 以保证并发请求中只有一个能够建立正式 Web 会话。
 */
@Mapper
interface AuthSsoLoginCodeMapper {
    /** 保存一次性短码摘要及有效期，不持久化可重放明文。 */
    fun insert(code: AuthSsoLoginCodePo): Int

    /** 以 FOR UPDATE 锁定仍有效且未消费的短码，必须在本地兑换事务中调用。 */
    fun lockUsable(codeHash: ByteArray): AuthSsoLoginCodeReadPo?

    /** 条件标记短码已消费，0 表示并发请求已抢先消费。 */
    fun consume(codeHash: ByteArray): Int
}
