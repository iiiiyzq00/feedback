package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 登录与授权核心 Mapper 契约测试。
 *
 * 冻结声明的数据表边界、XML 可解析性及关键锁/会话语句，防止格式整理或分层迁移改变 SQL 契约。
 */
class AuthCoreMyBatisContractTest {
    private val mapperTables = mapOf(
        "auth/AuthUserMapper.xml" to setOf("t_auth_user", "t_auth_user_role"),
        "auth/AuthUserRoleMapper.xml" to setOf("t_auth_user_role", "t_auth_user"),
        "auth/AuthRolePermissionMapper.xml" to setOf("t_auth_role_permission"),
        "auth/AuthExternalIdentityMapper.xml" to setOf("t_auth_external_identity"),
        "auth/AuthSsoStateMapper.xml" to setOf("t_auth_sso_state"),
        "auth/AuthSsoLoginCodeMapper.xml" to setOf("t_auth_sso_login_code"),
        "auth/AuthRoleMapper.xml" to setOf("t_auth_role"),
        "auth/AuthPermissionMapper.xml" to setOf("t_auth_permission"),
        "auth/AuthAdminDataPermissionMapper.xml" to setOf("t_auth_data_permission"),
        "auth/AuthFeishuRoleMappingMapper.xml" to setOf("t_auth_feishu_role_mapping"),
        "auth/AuthFeishuSyncRunMapper.xml" to setOf("t_auth_feishu_sync_run"),
        "auth/AuthPolicyDataPermissionMapper.xml" to setOf("t_auth_data_permission"),
        "auth/AuthFeishuOrgFactMapper.xml" to setOf("t_auth_feishu_user_org_fact"),
        "audit/SystemAuditMutationMapper.xml" to setOf("t_system_audit_log")
    )

    @Test
    fun `authentication mapper XML parses and stays inside declared tables`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mapperTables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE)
                .findAll(xml).map { it.value.lowercase() }.toSet()
            assertTrue(tables == expectedTable, "$name:$tables")
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.auth.AuthSsoStateMapper.findUsable"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.auth.AuthSsoStateMapper.lockUsable"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.auth.AuthUserMapper.incrementAuthVersionByRole"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.audit.SystemAuditMutationMapper.insert"))
    }

    @Test
    fun `authentication core has no direct SQL JDBC or JPA access`() {
        listOf(
            "src/main/kotlin/com/feedback/controller/auth/AuthController.kt",
            "src/main/kotlin/com/feedback/security/WebUserDetailsService.kt",
            "src/main/kotlin/com/feedback/security/LoginProtectionService.kt",
            "src/main/kotlin/com/feedback/security/AdminBootstrap.kt",
            "src/main/kotlin/com/feedback/service/impl/auth/FeishuSsoServiceImpl.kt",
            "src/main/kotlin/com/feedback/service/auth/FeishuRoleMappingService.kt",
            "src/main/kotlin/com/feedback/service/auth/FeishuAuthorizationPolicyService.kt"
        ).forEach { file ->
            val source = Files.readString(Path.of(file))
            listOf("JdbcTemplate", "NamedParameterJdbcTemplate", "JpaRepository", "EntityManager")
                .forEach { forbidden -> assertFalse(source.contains(forbidden), "$file:$forbidden") }
            assertFalse(Regex("\\b(SELECT.+FROM|INSERT\\s+INTO|UPDATE\\s+t_|DELETE\\s+FROM)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(source), file)
        }
    }
}
