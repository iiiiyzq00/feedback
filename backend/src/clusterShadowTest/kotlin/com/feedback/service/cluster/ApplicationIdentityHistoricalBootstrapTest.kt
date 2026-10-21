package com.feedback.service.cluster

import com.feedback.model.auth.RoleCode
import com.feedback.repository.auth.AuthGrantRepository
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.security.WebPrincipal
import com.feedback.service.ai.PromptAssetBootstrap
import com.feedback.service.applicationidentity.ApplicationIdentityShadowService
import com.feedback.service.jira.JiraAssigneeOwnerBackfillRunner
import com.feedback.service.normalization.FaultOccurrenceTimeBackfillRunner
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * 该测试会写入经审批的应用身份、alias、主问题影子事实和审计记录，因此必须通过独立环境变量显式启用。
 * 它不修改 Cluster、MAIN 或 ACTIVE generation，且在启动上下文中隔离所有无关的写型回填。
 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_IDENTITY_BOOTSTRAP_TEST_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "xxl.job.enabled=false",
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "spring.flyway.enabled=false",
    "spring.main.lazy-initialization=true"
])
class ApplicationIdentityHistoricalBootstrapTest @Autowired constructor(
    private val service: ApplicationIdentityShadowService,
    private val users: AuthUserRepository,
    private val grants: AuthGrantRepository
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @MockkBean(relaxed = true)
    private lateinit var jiraOwnerBackfill: JiraAssigneeOwnerBackfillRunner

    @Test
    fun `首批一百条应用身份建档后重复执行幂等收敛`() {
        val actorId = firstActiveAdminId()
        val actor = users.activeById(actorId) ?: error("APPLICATION_IDENTITY_ACTIVE_ADMIN_REQUIRED")
        val principal = WebPrincipal(
            userId = actorId,
            loginName = actor.username,
            passwordHash = actor.passwordHash.orEmpty(),
            displayName = actor.displayName,
            roles = setOf("ADMIN"),
            permissions = emptySet(),
            authVersion = actor.authVersion,
            authProvider = actor.authProvider
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            principal, null, principal.authorities
        )
        try {
            val first = service.process(0, 100, ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION)
            val second = service.process(0, 100, ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION)

            assertEquals(100, first.processedCount)
            assertEquals(first.nextSourceId, second.nextSourceId)
            assertEquals(first.resolvedCount, second.resolvedCount)
            assertEquals(first.unresolvedCount, second.unresolvedCount)
            assertEquals(first.ambiguousCount, second.ambiguousCount)
            assertEquals(0, second.createdIdentityCount)
            assertEquals(0, second.createdAliasCount)
            assertTrue(first.resolvedCount + first.unresolvedCount + first.ambiguousCount == first.processedCount)
            println(
                "APPLICATION_IDENTITY_BOOTSTRAP processed=${first.processedCount} resolved=${first.resolvedCount} " +
                    "unresolved=${first.unresolvedCount} ambiguous=${first.ambiguousCount} " +
                    "createdIdentities=${first.createdIdentityCount} createdAliases=${first.createdAliasCount} " +
                    "nextSourceId=${first.nextSourceId} replayCreatedIdentities=${second.createdIdentityCount} " +
                    "replayCreatedAliases=${second.createdAliasCount}"
            )
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    /**
     * 取一个启用中的管理员 ID 作为操作者。
     *
     * 刻意不走「按角色拉全量用户 ID」的查询：本仓库在授权控制面上明确避免把无界 ID 集合
     * 拉进 JVM（角色级会话失效走 `incrementAuthVersionByRole` 的集合式 SQL 更新），
     * 仓储接口里因此没有对应方法。这里改用有界分页 + 批量读角色，口径与授权管理页一致。
     */
    private fun firstActiveAdminId(): Long {
        val candidates = users.page(null, 0L, ADMIN_SCAN_LIMIT).mapNotNull { it.id }
        val rolesByUser = grants.rolesByUsers(candidates.toSet())
        return candidates.firstOrNull { RoleCode.ADMIN in rolesByUser[it].orEmpty() }
            ?: error("APPLICATION_IDENTITY_ADMIN_REQUIRED")
    }

    private companion object {
        /** 管理员查找的有界扫描上限；规模再大就该改成专用查询，而不是放大扫描。 */
        const val ADMIN_SCAN_LIMIT = 500
    }
}
