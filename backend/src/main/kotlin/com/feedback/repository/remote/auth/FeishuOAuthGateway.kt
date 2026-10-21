package com.feedback.repository.remote.auth

import com.feedback.model.result.auth.FeishuIdentity

/**
 * 飞书 OAuth 远程网关。
 *
 * 登录服务实现只依赖该稳定端口，不感知 OkHttp、access token 或飞书响应字段；
 * 网关负责超时、上游错误映射和敏感响应清理，不负责本地事务或身份落库。
 */
interface FeishuOAuthGateway {
    /** 用飞书授权码换取用户身份，内部完成 token 与 user_info 两段远程协议。 */
    fun exchange(code: String, redirectUri: String): FeishuIdentity
}
