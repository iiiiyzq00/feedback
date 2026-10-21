package com.feedback.service.auth

/**
 * Web 会话生命周期服务。
 *
 * 由退出登录入口调用，负责让用户的既有会话整体失效；浏览器 Cookie 的创建与清理由安全
 * 基础设施负责，不属于该业务接口。登录时间更新已归入本地/飞书认证各自的成功事务。
 */
interface AuthSessionLifecycleService {
    /** 提升用户会话版本，使该用户此前签发的所有服务端会话立即失效。 */
    fun invalidate(userId: Long)
}
