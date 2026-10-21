package com.feedback.security

import com.feedback.model.auth.RoleCode

import org.springframework.stereotype.Component

/**
 * 反馈来源外链的统一角色矩阵：Jira 仅 ADMIN/OPERATOR，红黑榜对已通过反馈访问校验的主体开放，
 * 其他渠道一律拒绝。这里只判断角色；绝对 HTTPS URI 结构校验和查看审计由详情应用服务完成。
 */
@Component
class ExternalLinkPolicy {
    /** 按强类型角色判断来源外链资格；未知渠道默认拒绝。 */
    fun mayOpen(channel: String, principal: WebPrincipal): Boolean = when (channel.uppercase()) {
        "REDBLACK" -> true
        "JIRA" -> principal.roles.any { it == RoleCode.ADMIN || it == RoleCode.OPERATOR }
        else -> false
    }
}
