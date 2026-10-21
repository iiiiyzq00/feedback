package com.feedback.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 登录页自助注册开关，默认关闭。
 *
 * 平台本身没有注册能力：账号只有两条来路——飞书 SSO 首次登录自动建档，或管理员在后台建号。本开关只为
 * 本地开发补一条 `POST /api/v1/auth/register`，注册出来的账号固定是本地口令账号 + 基础角色 [com.feedback.model.auth.RoleCode.USER]，
 * 不继承任何管理权限；口令策略、审计与租户口径与后台建号完全一致（同一套校验与服务端 BCrypt）。
 *
 * 关闭时注册入口按「不存在」处理（404），不对外暴露该功能是否存在。
 */
@Component
@ConfigurationProperties(prefix = "app.auth.registration")
class LocalRegistrationProperties {
    var enabled: Boolean = false

    /** 启动时记录注册入口状态；不输出任何凭据。 */
    @PostConstruct
    fun logState() {
        if (enabled) {
            logger.warn(
                "Self-service registration endpoint is ENABLED at POST /api/v1/auth/register: " +
                    "every registered account receives the base USER role only. Never enable this outside a local development machine."
            )
        } else {
            logger.info("Self-service registration endpoint: disabled")
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LocalRegistrationProperties::class.java)
    }
}
