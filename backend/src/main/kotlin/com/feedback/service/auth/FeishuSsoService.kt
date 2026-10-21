package com.feedback.service.auth

import com.feedback.model.auth.FeishuAuthorizeResult
import com.feedback.model.auth.FeishuCallbackResult
import com.feedback.model.auth.FeishuLoginResult

/**
 * 飞书浏览器登录服务。
 *
 * 控制器通过该接口发起授权、处理飞书回调并兑换一次性登录短码。
 * 接口不暴露 HTTP 客户端、MyBatis PO 或数据库锁细节；远程换票和本地事务由实现层编排。
 */
interface FeishuSsoService {
    /** 创建一次性 OAuth state，并返回浏览器应访问的飞书授权地址。 */
    fun authorize(returnPath: String?, requestOrigin: String? = null): FeishuAuthorizeResult

    /** 校验回调 state、交换飞书身份并返回携带本地一次性短码的前端地址。 */
    fun callback(code: String, state: String): FeishuCallbackResult

    /** 消费本地一次性登录短码，返回建立正式 Web 会话所需的用户快照和会话版本。 */
    fun exchangeLoginCode(code: String): FeishuLoginResult
}
