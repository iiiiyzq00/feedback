package com.feedback.service.impl.auth

import com.feedback.repository.auth.AuthSessionRepository
import com.feedback.repository.auth.LoginAuditRepository
import com.feedback.common.exception.AuthenticationSnapshotChangedException
import com.feedback.service.auth.LocalLoginSuccessService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 本地登录成功阶段实现。
 *
 * 这里只包含条件 UPDATE 和审计 INSERT；耗时的 BCrypt、Redis 与 Cookie 操作均已排除在事务外。
 */
@Service
class LocalLoginSuccessServiceImpl(
    private val sessions: AuthSessionRepository,
    private val audits: LoginAuditRepository,
) : LocalLoginSuccessService {
    /**
     * 以 expectedAuthVersion 条件更新最后登录时间，并在同一短写事务中追加成功审计。
     * CAS 未命中表示验密期间账号或授权已变化，整段事务失败并交由上层有限重试。
     */
    @Transactional
    override fun commit(userId: Long, username: String, expectedAuthVersion: Long, clientIp: String) {
        if (!sessions.recordSuccessfulLocalLogin(userId, expectedAuthVersion)) {
            throw AuthenticationSnapshotChangedException()
        }
        audits.recordSuccess(userId, username, clientIp)
    }
}
