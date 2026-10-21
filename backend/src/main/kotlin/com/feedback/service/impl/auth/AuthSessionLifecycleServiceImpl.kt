package com.feedback.service.impl.auth

import com.feedback.repository.auth.AuthSessionRepository
import com.feedback.service.auth.AuthSessionLifecycleService
import org.springframework.stereotype.Service

/**
 * Web 会话生命周期服务实现。
 *
 * 退出登录只落为一条 authVersion 原子 UPDATE，因此不额外声明事务；Repository 会校验影响
 * 行数，写入失败直接向上传播，防止控制器继续清理一个状态不一致的浏览器会话。
 */
@Service
class AuthSessionLifecycleServiceImpl(
    private val sessions: AuthSessionRepository,
) : AuthSessionLifecycleService {
    /** 通过单条递增语句废止用户当前版本之前签发的全部会话。 */
    override fun invalidate(userId: Long) {
        sessions.invalidateAllSessions(userId)
    }
}
