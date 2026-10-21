package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthUserMapper
import com.feedback.repository.auth.AuthSessionRepository
import org.springframework.stereotype.Repository

/**
 * 基于用户表 Mapper 的会话生命周期仓储实现。
 *
 * 每个方法只执行一条数据库原子更新，因此不在仓储方法上重复声明事务。本地登录条件更新以
 * false 表示快照过期；会话失效影响行数异常则失败关闭。
 */
@Repository
class MyBatisAuthSessionRepositoryImpl(
    private val users: AuthUserMapper,
) : AuthSessionRepository {
    /** 单条条件 UPDATE 同时校验认证快照并记录登录；未命中以 false 暴露给编排层。 */
    override fun recordSuccessfulLocalLogin(userId: Long, expectedAuthVersion: Long): Boolean =
        users.touchLastLoginIfAuthenticationCurrent(userId, expectedAuthVersion) == 1

    /** 原子提升 authVersion；影响行数异常时失败关闭，不能让调用方误判旧会话已失效。 */
    override fun invalidateAllSessions(userId: Long) {
        requirePersisted(users.incrementAuthVersion(userId) == 1, "AUTH_VERSION_UPDATE_FAILED")
    }

    /** 将数据库写入不变量统一转换为内部错误，触发调用方事务回滚。 */
    private fun requirePersisted(condition: Boolean, reasonCode: String) {
        if (!condition) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
        }
    }
}
