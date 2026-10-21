package com.feedback.repository.report

import com.feedback.mapper.report.ReportActorMapper
import com.feedback.mapper.report.ReportActorPermissionMapper
import com.feedback.mapper.report.ReportActorRoleMapper
import com.feedback.security.WebPrincipal
import org.springframework.stereotype.Repository

/** 报告执行人的活动账号、角色和权限快照。 */
@Repository
class ReportPrincipalRepository(
    private val actors: ReportActorMapper,
    private val roles: ReportActorRoleMapper,
    private val permissions: ReportActorPermissionMapper
) {
    fun principal(actorId: Long): WebPrincipal {
        val actor = actors.active(actorId) ?: reportRepositoryNotFound("REPORT_ACTOR_NOT_FOUND")
        val activeRoles = roles.active(actorId).toSet()
        val activePermissions = if (activeRoles.isEmpty()) emptySet() else permissions.byRoles(activeRoles.sorted()).toSet()
        return WebPrincipal(actorId, actor.username, "", actor.displayName, activeRoles, activePermissions, actor.authVersion)
    }
}
