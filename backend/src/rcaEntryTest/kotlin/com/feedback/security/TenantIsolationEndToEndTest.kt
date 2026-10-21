package com.feedback.security

import com.feedback.agent.rca.intake.RcaIntakeCandidatePort
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.TenantRegistryRepository
import com.feedback.service.admin.AdminManagementService
import com.feedback.service.admin.UserCreateInput
import com.feedback.service.auth.DataPermissionService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 租户隔离的端到端验证：真库（本机 3307）+ 真判定链。
 *
 * ## 它验证的是「谁能不能看到哪一行」，不是「有没有调用某个方法」
 *
 * 判定链上每一环都真的走：`t_application_identity.tenant_key`（租户权威值）
 * → `DataPermissionServiceImpl` 合并授权行 → `FeedbackAccessGuard` 的最终放行/拒绝，
 * 以及接入侧对同一个值的使用。断言一律落在**跨租户读取的最终结果**上。
 * 内存桩能验「permitsTenant 被调用过」，验不出这个链上任何一处把租户读飘了。
 *
 * ## 为什么本类在 rcaEntryTest 源集里
 *
 * 它是本仓库唯一一套「连真库、写真数据、按批次前缀清理」的套件（门控 `RCA_ENTRY_TEST_ENABLED`
 * 与 3307 断言见 `build.gradle.kts` 的 `rcaEntryTest` 任务）。本类与前一个落地此处的
 * `RerankWiringContractTest` 同属「跨模块接线必须对着真环境验」的一类，与 RCA 本身无关。
 *
 * ## 夹具是怎么造出来的（这是本项最大的诚实边界）
 *
 * * 租户、应用身份、来源、内容、事实、授权行：**由本类用 SQL 造**。
 *   仓库里没有「把某个应用身份划归某租户」的入口（应用身份由影子批次自动建立），
 *   也**没有租户分配界面**；这个赋值动作在本仓库就是运维动作，本类扮演的正是那个运维角色。
 *   `t_application_identity.tenant_key` 是权威值，不是替身——守卫与接入都从它现算租户。
 * * 账号那一行**不走 SQL**：走 `AuthUserRepository.createLocal`，即管理端建号最终执行的那条写入，
 *   租户随它落库。之所以不调 `AdminManagementService.createUser` 这个更外层的入口，见下条。
 *
 * ## 刻意**不**调用管理员建号服务：那张审计表是只追加的
 *
 * `t_system_audit_log` 上有 `BEFORE DELETE` 触发器（`trg_audit_log_no_delete`，实测抛
 * `AUDIT_LOG_APPEND_ONLY`），审计行**删不掉**。在一个共享的部署库上跑测试就不该产生
 * 不可撤销的痕迹，所以夹具账号改用 `AuthUserRepository.createLocal` 直接建
 * ——那正是 `AdminManagementServiceImpl.createUser` 最终执行的那条写入，租户参数同形，
 * 唯一的差别是它绕过服务层、因此不写审计。服务层只在**拒绝**那一支被真正调用
 * （阶段 6：未注册租户必须在校验处、也就是任何写入之前被拒，那一支不产生审计行）。
 *
 * **实测边界要说清**：成功路径验到仓储层，拒绝路径验到服务层；
 * 「服务层成功建号」（即含审计写入的完整链路）本类刻意不覆盖。
 *
 * ## 顺序是状态迁移，所以只有一个用例
 *
 * 各阶段各自只在上一阶段之后才有意义，拆成多个 `@Test` 会变成依赖执行顺序的用例，
 * 且失败时看不出是哪一步把状态带偏的。单用例里每段带编号断言，失败信息里能直接读出阶段。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 这里以前跟了一行 `app.rca.worker-id=fpten-isolation-test`，是为一个与本类
        // 无关的缺陷让路：`rcaWorker` 曾是无条件 `@Bean`，构造期要求非空 worker-id，
        // 于是任何全上下文用例都得填一个。2026-10-09 改成条件装配后
        // （见 `config/RcaRuntimeConfiguration.kt`），开关关着时那个 Bean 不存在。
        // 本类只验租户隔离，不该背着一个执行侧的身份配置。
        // 本机 glibc 2.31 跑不了 DJL native 分词；与本类目标无关，覆盖掉以免整上下文起不来。
        "app.ai.embedding.provider=hashing"
    ]
)
class TenantIsolationEndToEndTest @Autowired constructor(
    private val guard: FeedbackAccessGuard,
    private val permissions: DataPermissionService,
    private val adminService: AdminManagementService,
    private val userDetails: WebUserDetailsService,
    private val tenants: TenantRegistryRepository,
    private val users: AuthUserRepository,
    private val candidates: RcaIntakeCandidatePort,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    /** 本类独有的批次前缀；清理只删匹配它的行。 */
    private val tag = "fpten" + System.nanoTime().toString().takeLast(9)

    private val tenantA = tag.uppercase() + "A"
    private val tenantB = tag.uppercase() + "B"

    /** 应用身份的 public_id 在造数时固定下来，供清理与断言使用。 */
    private val identityA = java.util.UUID.randomUUID().toString()
    private val identityB = java.util.UUID.randomUUID().toString()
    private var sourceA: Long = 0
    private var sourceB: Long = 0
    private var accountId: Long = 0

    @Test
    fun `跨租户读取默认失败关闭，显式授权才放开，DENY 始终优先`() {
        assertTrue(datasourceUrl.contains(":3307"), "本测试只允许连本机 feedback-platform 的 3307")

        // ---- 阶段 0：造数 ----
        registerTenants()
        createApplicationIdentities()
        createFeedbackRows()
        accountId = createAccountWithTenant(tenantA)
        assertEquals(
            tenantA,
            jdbc.queryForObject("SELECT tenant_key FROM t_auth_user WHERE id = ?", String::class.java, accountId),
            "建号时指定的租户没有落到 t_auth_user.tenant_key —— 账号侧租户没有写入方"
        )

        // 主体从库里装配，租户必须来自 t_auth_user 而不是测试自己填的常量。
        val stored = userDetails.loadUserById(accountId)
        assertEquals(tenantA, stored.tenantKey, "主体租户不是从账号行装配出来的")
        // 守卫要求 SSO 来源 + 工作区权限；这里只换掉这两项，租户沿用**从库里读到的那个值**。
        install(
            WebPrincipal(
                userId = stored.userId,
                loginName = stored.username,
                passwordHash = "",
                displayName = null,
                roles = setOf(RoleCode.PHASE_ONE_TECHNICAL),
                permissions = setOf(PlatformPermissions.WORKSPACE),
                authProvider = AuthProvider.SSO,
                tenantKey = stored.tenantKey,
            )
        )

        // ---- 阶段 1：默认收紧 —— 自己的租户能读，别人的读不到 ----
        assertEquals(tenantA, guard.require(sourceA).facts.tenant, "1a 本租户读取应通过")
        // 中间层单独看一眼：守卫报「不存在」时，原因应当是范围里就没有 B，而不是守卫自己写死了拒绝。
        assertTrue(!permissions.scope().permitsTenant(tenantB), "1b 无 TENANT 授权行时范围里不应出现别的租户")
        val denied = assertThrows<ClientException> { guard.require(sourceB) }
        assertEquals(ErrorCode.NOT_FOUND, denied.errorCode, "1c 跨租户必须报「不存在」而不是「拒绝」")
        assertEquals("FEEDBACK_NOT_FOUND", denied.reasonCode, "1d 跨租户的原因码必须与「记录不存在」同一个")

        // ---- 阶段 2：显式 ALLOW 放开跨租户 ----
        grantTenant(tenantB, "ALLOW")
        assertEquals(tenantB, guard.require(sourceB).facts.tenant, "2a 显式 ALLOW 应放开该租户")
        assertEquals(tenantA, guard.require(sourceA).facts.tenant, "2b 放开别人不应影响自己")

        // ---- 阶段 3：把这一行从 ALLOW 改成 DENY ----
        // 注意这里是**改**而不是再加一行：`t_auth_data_permission` 上除 uk_auth_data_scope（含 effect）
        // 之外还有更严的 uk_subject_scope（不含 effect），所以同一个 scope_value 只允许存在一行
        // ——ALLOW 与 DENY 在库层面就不可能同时挂在一个租户上。收紧只能是改这一行的效果。
        grantTenant(tenantB, "DENY")
        val deniedAgain = assertThrows<ClientException> { guard.require(sourceB) }
        assertEquals(ErrorCode.NOT_FOUND, deniedAgain.errorCode, "3a 同一行改成 DENY 后必须重新收紧")
        assertEquals(tenantA, guard.require(sourceA).facts.tenant, "3b 收紧别人不应波及自己")

        // ---- 阶段 4：通配 ALLOW 与具体 DENY 并存 —— 失败关闭 ----
        // 通配放开是「不限租户」，但带排除项时无法用有限集合安全表达，因此整体失败关闭
        // （与 MODULE/REGION 对通配的处理同一个口径）。这一条如果退化成「忽略 DENY」，
        // 就会变成一个静默的跨租户全开——正是最该被测试钉住的那种退化。
        grantTenant("*", "ALLOW")
        assertThrows<ClientException> { guard.require(sourceB) }
        val deniedByWildcard = assertThrows<ClientException> { guard.require(sourceA) }
        assertEquals(
            ErrorCode.NOT_FOUND, deniedByWildcard.errorCode,
            "4a 通配 ALLOW + 具体 DENY 必须失败关闭（连自己租户也拒），不能忽略 DENY"
        )
        // 去掉 DENY 后通配 ALLOW 才真正「不限租户」。
        clearTenantGrant(tenantB)
        assertEquals(tenantB, guard.require(sourceB).facts.tenant, "4b 只留通配 ALLOW 时应不限租户")
        assertEquals(tenantA, guard.require(sourceA).facts.tenant, "4c 通配 ALLOW 下自己租户同样可读")
        clearTenantGrant("*")

        // ---- 阶段 5：接入侧现算的租户与守卫看到的是同一个值 ----
        // 防的是「守卫按应用身份现算、接入按别的东西取租户」那种两边分叉：租户是
        // uk_episode_partition 的组成部分，两边不一致会把证据并进错误的问题对象。
        val batch = candidates.findAfter(sourceA - 1, PRODUCTION, 50)
        assertEquals(tenantA, batch.firstOrNull { it.sourceId == sourceA }?.tenantKey, "5a 接入侧租户与守卫不一致")
        assertEquals(tenantB, batch.firstOrNull { it.sourceId == sourceB }?.tenantKey, "5b 接入侧租户与守卫不一致")

        // ---- 阶段 6：未注册租户不得被分配，且失败不留痕 ----
        // 这一阶段是**唯一**走管理服务（而不是仓储）的一处，目的正相反于阶段 0：
        // 验的是服务层那道租户校验真的在写库之前把未注册租户挡下来。
        // 之所以能这样做而不产生删不掉的审计行，是因为校验在 require 里，
        // 位置早于 createLocal 与 audit(...)——下面两个计数断言就是在钉这一点。
        assertTrue(tenants.activeKeys().containsAll(listOf(tenantA, tenantB)), "注册表读不到刚建的租户")
        assertTrue(
            jdbc.queryForObject("SELECT COUNT(*) FROM t_auth_tenant WHERE tenant_key = ?", Long::class.java, ghostTenant) == 0L,
            "ghost 租户不该存在于注册表"
        )
        val usersBefore = userCount()
        val auditsBefore = auditCount()
        install(operatorPrincipal())
        val rejected = assertThrows<Exception> {
            adminService.createUser(
                UserCreateInput(
                    username = "$tag" + "ghost",
                    displayName = null,
                    password = "ghost-password-1234",
                    tenantKey = ghostTenant,
                )
            )
        }
        assertTrue(
            rejected.message?.contains("租户") == true,
            "未注册租户应当被租户校验明确拒绝，而不是落到外键或别的错误上：${rejected.message}"
        )
        assertEquals(usersBefore, userCount(), "未注册租户建号失败后不应该留下账号")
        assertEquals(auditsBefore, auditCount(), "未注册租户被拒时不应该写审计（校验必须早于任何写入）")

        println(
            "TENANT_ISOLATION tag=$tag tenantA=$tenantA tenantB=$tenantB " +
                "sourceA=$sourceA sourceB=$sourceB accountId=$accountId " +
                "candidateTenants=${batch.map { it.sourceId to it.tenantKey }}"
        )
    }

    /** 未注册租户；用来验证分配动作在校验处就失败。 */
    private val ghostTenant = tag.uppercase() + "GHOST"

    /** 建两个租户：它们是 tenant_key 的封闭取值来源，必须存在于注册表里。 */
    private fun registerTenants() {
        listOf(tenantA, tenantB).forEach { key ->
            jdbc.update(
                "INSERT INTO t_auth_tenant (tenant_key, display_name) VALUES (?, ?)",
                key, "$tag 租户 $key"
            )
        }
    }

    /**
     * 两个应用身份，分别归属 A、B。
     *
     * 这一步就是「租户分配」本身——本仓库没有它的入口，所以由测试直接写。
     * 它不是替身：守卫与接入都从这里现算租户，写错这一列下游看到的就全是错的。
     */
    private fun createApplicationIdentities() {
        listOf(identityA to tenantA, identityB to tenantB).forEach { (uuid, tenant) ->
            jdbc.update(
                "INSERT INTO t_application_identity (public_id, display_name, tenant_key, lifecycle_status, mapping_version) " +
                    "VALUES (UUID_TO_BIN(?), ?, ?, 'ACTIVE', 1)",
                uuid, "$tag 应用 $tenant", tenant
            )
        }
    }

    /** 两条来源 + 内容 + 主问题事实；PRODUCTION 且无 fixture_tag（chk_source_fixture_tag 要求二者互斥）。 */
    private fun createFeedbackRows() {
        sourceA = createSource(identityA, "A")
        sourceB = createSource(identityB, "B")
    }

    private fun createSource(identityUuid: String, suffix: String): Long {
        jdbc.update(
            """
            INSERT INTO t_feedback_source
              (public_id, source_channel, source_record_id, source_event_time, source_update_time,
               data_environment, fixture_tag, app_version)
            VALUES (UNHEX(REPLACE(UUID(), '-', '')), 'SDK', ?, NOW(3), NOW(3), 'PRODUCTION', NULL, '1.0.0')
            """.trimIndent(),
            "$tag-$suffix"
        )
        // 按唯一键回读，**不用 LAST_INSERT_ID()**：JdbcTemplate 每次操作各自取连接，
        // 不在事务里时那两条语句可能落在不同连接上，读到的会是 0 而不是刚插入的 id。
        val id = jdbc.queryForObject(
            "SELECT id FROM t_feedback_source WHERE source_channel = 'SDK' AND source_record_id = ?",
            Long::class.java,
            "$tag-$suffix"
        )!!
        jdbc.update(
            "INSERT INTO t_feedback_content (source_id, masking_status, is_deleted, title, description) " +
                "VALUES (?, 'PASS', 0, ?, ?)",
            id, "$tag 标题 $suffix", "$tag 描述 $suffix"
        )
        // RESOLVED 这一支有四个非空要求（chk_primary_fact_resolution）：身份 id、公开 id 快照、
        // 展示名快照、映射版本必须同时给出。少给任何一个都会被约束挡下——这是刻意的，
        // 因为「已解析」的事实少了这些字段就无法解释它是怎么解析出来的。
        jdbc.update(
            """
            INSERT INTO t_feedback_primary_problem_fact
              (feedback_source_id, application_identity_id, application_public_id_snapshot,
               application_display_name_snapshot, application_mapping_version,
               application_resolution_status, classification_status, resolution_rule_version, resolved_alias_count)
            SELECT ?, ai.id, ai.public_id, ai.display_name, ai.mapping_version,
                   'RESOLVED', 'PENDING', 'fpten-test', 1
            FROM t_application_identity ai WHERE ai.public_id = UUID_TO_BIN(?)
            """.trimIndent(),
            id, identityUuid
        )
        return id
    }

    /**
     * 走管理端建号最终执行的那条仓储写入建号；租户随之一并落库。
     *
     * `AuthUserPo.tenantKey` **不给默认值**：这里刻意显式传，任何一侧改了默认口径都会在这里暴露。
     */
    private fun createAccountWithTenant(tenant: String): Long = users.createLocal(
        AuthUserPo(
            username = "$tag" + tenant.takeLast(2).lowercase() + System.nanoTime().toString().takeLast(4),
            displayName = "$tag 账号",
            passwordHash = "not-a-real-hash",
            authProvider = AuthProvider.LOCAL.code,
            tenantKey = tenant,
        )
    )

    /**
     * 增改 TENANT 授权行。
     *
     * 先 UPDATE 再 INSERT：`uk_subject_scope` 不允许同一 scope_value 出现两行，
     * 所以「从 ALLOW 改成 DENY」在库里就是一次更新，不能靠插第二行。
     */
    private fun grantTenant(tenant: String, effect: String) {
        val updated = jdbc.update(
            "UPDATE t_auth_data_permission SET effect = ? WHERE subject_type = 'USER' AND subject_id = ? " +
                "AND scope_type = 'TENANT' AND scope_value = ? AND access_level = 'READ'",
            effect, accountId.toString(), tenant
        )
        if (updated == 0) {
            jdbc.update(
                "INSERT INTO t_auth_data_permission (subject_type, subject_id, scope_type, scope_value, access_level, effect) " +
                    "VALUES ('USER', ?, 'TENANT', ?, 'READ', ?)",
                accountId.toString(), tenant, effect
            )
        }
    }

    private fun clearTenantGrant(tenant: String) {
        jdbc.update(
            "DELETE FROM t_auth_data_permission WHERE subject_type = 'USER' AND subject_id = ? " +
                "AND scope_type = 'TENANT' AND scope_value = ?",
            accountId.toString(), tenant
        )
    }

    private fun userCount(): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM t_auth_user WHERE username LIKE ?", Long::class.java, "$tag%")!!

    /** 本类操作者名下的审计行数；用来证明「被拒的建号没有留下任何写入」，也用来盯住残留。 */
    private fun auditCount(): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_system_audit_log WHERE user_id = ?", Long::class.java, OPERATOR_USER_ID
        )!!

    /**
     * 建号动作的操作者。
     *
     * 只被 `AdminManagementServiceImpl.actorId()` 读一次（写进审计），**不参与任何权限判定**。
     * userId 取一个不可能与真实账号相撞的值，好让万一残留的审计行一眼能认出是本测试产生的。
     */
    private fun operatorPrincipal() = WebPrincipal(
        userId = OPERATOR_USER_ID,
        loginName = "$tag-operator",
        passwordHash = "",
        displayName = null,
        roles = setOf(RoleCode.ADMIN),
        permissions = setOf(PlatformPermissions.WORKSPACE),
        authProvider = AuthProvider.SSO,
        tenantKey = TenantKeys.DEFAULT,
    )

    private fun install(principal: WebPrincipal) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
    }

    @AfterEach
    fun cleanup() {
        // 顺序即外键依赖顺序：先删引用方，再删被引用方。
        // 每条都带 $tag 前缀条件——一次误删库里别的数据比一次清理不干净严重得多。
        //
        // 比较 varchar 列与 CAST(id AS CHAR) 时必须显式带 COLLATE：库/表是 utf8mb4_0900_as_cs，
        // 而 CAST 出来的串取连接排序规则（ai_ci），两个 IMPLICIT 排序规则相遇是**报错**而不是
        // 退化成一条规则（实测 error 1267）。绑定参数不受影响：参数可强制性更高，会直接被列规则采纳。
        //
        // **没有删 t_system_audit_log 的那一条**：它是只追加的（trg_audit_log_no_delete），
        // 删只会抛 AUDIT_LOG_APPEND_ONLY。这也是本类不调管理端建号服务的原因，见类注释。
        jdbc.update(
            "DELETE FROM t_auth_data_permission WHERE subject_type = 'USER' AND subject_id IN " +
                "(SELECT CAST(id AS CHAR) COLLATE utf8mb4_0900_as_cs FROM t_auth_user WHERE username LIKE ?)",
            "$tag%"
        )
        jdbc.update(
            "DELETE FROM t_auth_user_role WHERE user_id IN (SELECT id FROM t_auth_user WHERE username LIKE ?)",
            "$tag%"
        )
        jdbc.update(
            "DELETE FROM t_feedback_primary_problem_fact WHERE feedback_source_id IN " +
                "(SELECT id FROM t_feedback_source WHERE source_record_id LIKE ?)", "$tag%"
        )
        // 这一行不是本测试写的，是**数据库触发器** `trg_feedback_content_workflow_default`
        // 在往 t_feedback_content 插行时建的（实测：漏掉它会让删来源那一步撞上外键 1451）。
        // 触发器建的默认工作流状态属于夹具的一部分，因此必须随夹具一起删。
        jdbc.update(
            "DELETE FROM t_feedback_workflow_state WHERE feedback_source_id IN " +
                "(SELECT id FROM t_feedback_source WHERE source_record_id LIKE ?)", "$tag%"
        )
        jdbc.update(
            "DELETE FROM t_feedback_content WHERE source_id IN " +
                "(SELECT id FROM t_feedback_source WHERE source_record_id LIKE ?)", "$tag%"
        )
        jdbc.update("DELETE FROM t_feedback_source WHERE source_record_id LIKE ?", "$tag%")
        jdbc.update("DELETE FROM t_application_identity WHERE display_name LIKE ?", "$tag%")
        jdbc.update("DELETE FROM t_auth_user WHERE username LIKE ?", "$tag%")
        jdbc.update("DELETE FROM t_auth_tenant WHERE tenant_key LIKE ?", tag.uppercase() + "%")
        SecurityContextHolder.clearContext()
    }

    companion object {
        private const val PRODUCTION = "PRODUCTION"

        /** 不可能与真实账号相撞的审计主体 id（真实 id 是自增小整数）。 */
        private const val OPERATOR_USER_ID = 990_000_001L

        /** 只补**测试专用**的固定密钥；数据库地址必须来自环境，理由见 `RcaIntakeEndToEndTest`。 */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("dubbo.enabled") { "false" }
        }
    }
}
