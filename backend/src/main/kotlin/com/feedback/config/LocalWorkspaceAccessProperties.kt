package com.feedback.config

import com.feedback.security.PrincipalAccess
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 本地开发用的工作区准入开关，默认关闭。
 *
 * 生产语义下工作区页面只对 SSO 主体开放（见 [PrincipalAccess.hasWorkspace]）：口令登录的主体来源是 LOCAL，
 * 因此在没有真实飞书租户的机器上，任何账号密码登录都读不到反馈/问题分析页面。打开本开关只在本进程内放开
 * 「来源必须是 SSO」这一条；工作区权限（`access:phase_one_workspace`）仍然必须显式具备，角色本身不放行。
 *
 * 该开关只应出现在本机运行脚本里，绝不进入生产配置模板；打开时启动日志会给出显式告警。
 */
@Component
@ConfigurationProperties(prefix = "app.security.local-workspace")
class LocalWorkspaceAccessProperties {
    var enabled: Boolean = false

    /** 把开关值下发给进程内的准入解释器；解释器是静态对象，无法按 Bean 注入。 */
    @PostConstruct
    fun applyAndLog() {
        PrincipalAccess.localWorkspaceEnabled = enabled
        if (enabled) {
            logger.warn(
                "Local workspace access switch is ENABLED: LOCAL (password) principals holding the workspace permission " +
                    "can now read feedback workspace routes. This is a local-development-only relaxation of the SSO-only rule."
            )
        } else {
            logger.info("Local workspace access switch: disabled (workspace routes require an SSO principal)")
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(LocalWorkspaceAccessProperties::class.java)
    }
}
