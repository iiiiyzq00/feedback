package com.feedback.security

import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component

/**
 * 反馈路由层统一授权矩阵。工作区路由要求已认证 SSO 主体和 `access:phase_one_workspace`；
 * 问题簇详情额外兼容 `access:ai_reports`。这里只决定路由资格，具体反馈的数据范围和字段策略仍由服务层复核。
 */
@Component
class AccessAuthorization {
    /** 反馈渠道路由要求 SSO 工作区权限，具体数据范围由服务层继续校验。 */
    fun channel(): AuthorizationManager<RequestAuthorizationContext> = manager()
    /** 导出路由沿用反馈工作区资格，导出权限由方法级规则叠加。 */
    fun channelExport(): AuthorizationManager<RequestAuthorizationContext> = manager()
    /** 分析路由要求 SSO 工作区权限，不能仅凭历史角色码进入。 */
    fun analysis(): AuthorizationManager<RequestAuthorizationContext> = manager()
    /** 问题簇详情兼容既有 AI 报告权限，同时保留工作区入口。 */
    fun clusterDetail(): AuthorizationManager<RequestAuthorizationContext> = AuthorizationManager { authentication, _ ->
        val auth = authentication.get()
        val principal = auth.principal as? WebPrincipal
        AuthorizationDecision(
            auth.isAuthenticated && principal != null && PrincipalAccess.hasWorkspaceOrAiReports(principal)
        )
    }

    /** 阻止反馈限定角色或非管理员工作区主体进入反馈范围外的旧路由。 */
    fun outsideWorkspace(): AuthorizationManager<RequestAuthorizationContext> = AuthorizationManager { authentication, _ ->
        val auth = authentication.get()
        val principal = auth.principal as? WebPrincipal
        AuthorizationDecision(
            auth.isAuthenticated && principal != null && !PrincipalAccess.isWorkspaceOnly(principal)
        )
    }

    /** 构造统一工作区 AuthorizationManager，缺失或非 Web 主体时失败关闭。 */
    private fun manager() = AuthorizationManager<RequestAuthorizationContext> { authentication, _ ->
        val auth = authentication.get()
        val principal = auth.principal as? WebPrincipal
        AuthorizationDecision(auth.isAuthenticated && principal != null && PrincipalAccess.hasWorkspace(principal))
    }
}
