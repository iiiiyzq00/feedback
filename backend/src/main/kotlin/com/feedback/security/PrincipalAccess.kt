package com.feedback.security

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode

/**
 * 反馈公共服务的主体资格解释器。
 *
 * 路由授权仍负责 HTTP 边界，本对象为可被其他入口直接调用的应用服务提供同口径纵深防御。
 * 调用方必须在读取 generation、反馈存在性或其他业务事实前完成校验。
 */
object PrincipalAccess {
    val WORKSPACE_PERMISSION = PlatformPermissions.WORKSPACE
    private val FEEDBACK_ROLES = setOf(RoleCode.PHASE_ONE_TECHNICAL, RoleCode.PHASE_ONE_PRODUCT)

    /**
     * 本地开发放宽：允许 LOCAL（账号密码）主体凭显式工作区权限进入工作区。
     *
     * 由 `app.security.local-workspace.enabled` 驱动（见 config/LocalWorkspaceAccessProperties.kt），
     * **默认 false**，即生产语义不变：工作区只认 SSO 来源。该字段是进程级一次性下发的静态开关，
     * 因为本解释器是 object，不能被按 Bean 注入；除启动期外不再改写。
     */
    @Volatile
    var localWorkspaceEnabled: Boolean = false

    /** 工作区资格要求显式工作区权限；来源默认必须是 SSO，仅当本地放宽开关打开时允许 LOCAL。 */
    fun hasWorkspace(principal: WebPrincipal): Boolean =
        WORKSPACE_PERMISSION in principal.permissions &&
            (principal.authProvider == AuthProvider.SSO || localWorkspaceEnabled)

    /** 问题簇读取兼容既有 AI 报告权限，其余反馈路由仍要求工作区资格。 */
    fun hasWorkspaceOrAiReports(principal: WebPrincipal): Boolean =
        hasWorkspace(principal) || PlatformPermissions.AI_REPORTS in principal.permissions

    /** 判断主体是否应被限制在反馈工作区路由内，管理员不受该兼容限制。 */
    fun isWorkspaceOnly(principal: WebPrincipal): Boolean =
        principal.roles.any(FEEDBACK_ROLES::contains) ||
            (WORKSPACE_PERMISSION in principal.permissions && RoleCode.ADMIN !in principal.roles)

    /** 强制工作区资格并返回原主体，失败时使用稳定禁止访问错误。 */
    fun requireWorkspace(principal: WebPrincipal): WebPrincipal {
        if (!hasWorkspace(principal)) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "FEEDBACK_WORKSPACE_PERMISSION_REQUIRED")
        }
        return principal
    }

    /** 问题簇兼容读取允许既有 AI 报告权限；反馈工作区分支仍要求工作区资格（来源默认 SSO，见上方开关）。 */
    fun requireWorkspaceOrAiReports(principal: WebPrincipal): WebPrincipal {
        if (!hasWorkspaceOrAiReports(principal)) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "CLUSTER_READ_PERMISSION_REQUIRED")
        }
        return principal
    }
}
