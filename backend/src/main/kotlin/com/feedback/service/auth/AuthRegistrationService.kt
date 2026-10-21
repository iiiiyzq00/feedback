package com.feedback.service.auth

/**
 * 登录页自助注册契约。
 *
 * 实现负责开关判定、用户名/口令策略、最小授权与审计；调用方（HTTP 边界）只做入参校验和限流。
 * 失败一律抛出客户端可修正的异常，不泄露「账号是否存在」以外的内部事实。
 */
interface AuthRegistrationService {

    /**
     * 创建一个本地口令账号并授予基础角色，返回新账号 ID。
     *
     * @param username 账号名，`^[A-Za-z0-9_.-]{3,50}$`
     * @param password 明文口令，长度 12..128（平台统一口令策略）
     * @param displayName 展示名，可空
     * @param clientIp 调用方地址，仅用于安全审计
     */
    fun register(username: String, password: String, displayName: String?, clientIp: String): Long
}
