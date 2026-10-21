package com.feedback.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/** 权限链分层、强业务类型、事务一致性与 SQL 可读性反回归门禁。 */
class PermissionChainArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    /**
     * 已完成登录/权限重构的完整调用链目录。目录内未来新增文件会自动进入统一验收；
     * 跨域迁移时应把对应调用链目录加入这里，不能依赖人工记住逐文件登记。
     */
    private val documentedPermissionRoots = listOf(
        "src/main/kotlin/com/feedback/mapper/auth",
        "src/main/kotlin/com/feedback/model/auth",
        "src/main/kotlin/com/feedback/model/po/auth",
        "src/main/kotlin/com/feedback/model/result/auth",
        "src/main/kotlin/com/feedback/repository/auth",
        "src/main/kotlin/com/feedback/repository/remote/auth",
        "src/main/kotlin/com/feedback/service/impl/auth",
        "src/main/kotlin/com/feedback/service/auth",
        "src/main/kotlin/com/feedback/repository/impl/auth",
        "src/main/kotlin/com/feedback/controller/auth",
        "src/main/kotlin/com/feedback/security",
    )

    /** 与权限链直接相关、但不能通过整个目录纳入的生产文件。 */
    private val documentedPermissionFiles = listOf(
        "src/main/kotlin/com/feedback/service/impl/admin/AdminManagementServiceImpl.kt",
        "src/main/kotlin/com/feedback/service/admin/AdminManagementService.kt",
        "src/main/kotlin/com/feedback/controller/admin/AdminManagementController.kt",
        "src/main/kotlin/com/feedback/config/FeishuSsoProperties.kt",
        "src/main/kotlin/com/feedback/config/WebSecurityConfig.kt",
    )

    @Test
    fun `permission entrypoints depend on contracts instead of implementation or persistence`() {
        listOf(
            "src/main/kotlin/com/feedback/controller/admin/AdminManagementController.kt",
            "src/main/kotlin/com/feedback/controller/auth/AuthController.kt",
            "src/main/kotlin/com/feedback/security/WebSessionAuthenticationFilter.kt",
        ).forEach { file ->
            val content = source(file)
            assertFalse(content.contains("import com.feedback.service.impl."), "$file imports ServiceImpl")
            assertFalse(content.contains("import com.feedback.mapper."), "$file imports Mapper")
            assertFalse(content.contains("import com.feedback.repository."), "$file imports Repository")
        }
    }

    @Test
    fun `login and permission consumers do not reintroduce registered scalar literals`() {
        val forbidden = Regex(
            "\\\"(?:ADMIN|OPERATOR|AUDITOR|USER|PHASE_ONE_TECHNICAL|PHASE_ONE_PRODUCT|LOCAL|SSO|" +
                "CHANNEL|MODULE|REGION|FIELD|ALLOW|DENY|READ|WRITE|ROLE|DATA_SCOPE|" +
                "DRAFT|APPROVED|REJECTED|RETIRED|RUNNING|COMPLETED|PARTIAL_SUCCESS|FAILED|ACTIVE|INACTIVE|" +
                "access:[a-z0-9_]+|action:[a-z0-9_]+|admin:manage)\\\"",
        )
        val dictionary = projectDir.resolve("src/main/kotlin/com/feedback/model/auth/AuthorizationTypes.kt")
        val violations = permissionSourceFiles().filterNot { it == dictionary }.flatMap { file ->
            forbidden.findAll(Files.readString(file))
                .map { "${projectDir.relativize(file)} -> ${it.value}" }
                .toList()
        }
        assertTrue(violations.isEmpty(), "Permission scalar literals must use domain types/catalog:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `principal and authorization repositories expose strong types`() {
        val principal = source("src/main/kotlin/com/feedback/security/WebPrincipal.kt")
        assertTrue(principal.contains("val roles: Set<RoleCode>"))
        assertTrue(principal.contains("val permissions: Set<PermissionCode>"))
        assertTrue(principal.contains("val authProvider: AuthProvider"))
        val grants = source("src/main/kotlin/com/feedback/repository/auth/AuthCoreRepositories.kt")
        assertTrue(grants.contains("interface AuthGrantRepository"))
        assertTrue(grants.contains("Set<RoleCode>"))
        assertTrue(grants.contains("Set<PermissionCode>"))
        assertTrue(grants.contains("interface AuthRoleCatalogRepository"))
        assertTrue(grants.contains("interface AuthAdminDataPermissionRepository"))
        assertTrue(grants.contains("interface FeishuRoleMappingRepository"))
        assertTrue(grants.contains("interface FeishuPolicyRepository"))
        val data = source("src/main/kotlin/com/feedback/repository/auth/AuthDataPermissionRepository.kt")
        assertTrue(data.contains("interface AuthDataPermissionRepository"))
        assertFalse(data.contains("AuthDataPermissionPo"))
    }

    @Test
    fun `permission changes atomically invalidate sessions and append audits`() {
        val admin = source("src/main/kotlin/com/feedback/service/impl/admin/AdminManagementServiceImpl.kt")
        mapOf(
            "replaceRolePermissions" to "authUsers.invalidateSessionsByRole",
            "assignRoles" to "authUsers.invalidateSessions",
            "replaceDataPermissions" to "authUsers.invalidateSessions",
        ).forEach { (method, invalidation) ->
            val body = admin.substringAfter("override fun $method").substringBefore("\n    }")
            assertTrue(body.contains(invalidation), "$method must invalidate sessions")
            assertTrue(body.contains("audit("), "$method must append security audit")
        }
        val policy = source("src/main/kotlin/com/feedback/service/impl/auth/FeishuAuthorizationPolicyServiceImpl.kt")
        val changed = policy.substringAfter("if (changed) {").substringBefore("\n        }")
        assertTrue(changed.contains("users.updateSsoActiveAndVersion"))
        assertTrue(changed.contains("audits.append"))
    }

    @Test
    fun `permission hot path and control plane keep bounded transaction work`() {
        listOf("allowedChannels", "scope", "scopeFor", "fieldPolicy", "snapshot").forEach { methodName ->
            val method = com.feedback.service.impl.auth.DataPermissionServiceImpl::class.java.declaredMethods
                .single { it.name == methodName && !it.isBridge }
            assertFalse(method.isAnnotationPresent(Transactional::class.java), "$methodName is a single-query hot path")
        }

        val admin = source("src/main/kotlin/com/feedback/service/impl/admin/AdminManagementServiceImpl.kt")
        val roleReplacement = admin.substringAfter("override fun replaceRolePermissions").substringBefore("\n    }")
        assertTrue(roleReplacement.contains("invalidateSessionsByRole(role)"))
        assertFalse(roleReplacement.contains("userIds(role)"), "role permission changes must not load unbounded user IDs")
        val assignment = admin.substringAfter("override fun assignRoles").substringBefore("\n    }")
        assertTrue(assignment.indexOf("lockRoleMutationFence()") < assignment.indexOf("lockUser(id)"))

        val userXml = source("src/main/resources/mybatis/mapper/auth/AuthUserMapper.xml")
        val byRole = userXml.substringAfter("<update id=\"incrementAuthVersionByRole\">").substringBefore("</update>")
        assertTrue(byRole.contains("INNER JOIN t_auth_user_role"))
        assertFalse(byRole.contains("<foreach"), "role-wide authVersion invalidation must remain set based")

        val policy = source("src/main/kotlin/com/feedback/service/impl/auth/FeishuAuthorizationPolicyServiceImpl.kt")
        val userLoop = policy.substringAfter("identityRows.forEach").substringBefore("if (result.first)")
        assertTrue(userLoop.indexOf("val evaluation = evaluate(") < userLoop.indexOf("transactions.execute"))
        assertTrue(userLoop.indexOf("val desiredScopes =") < userLoop.indexOf("transactions.execute"))
        assertTrue(userLoop.indexOf("val groupsJson =") < userLoop.indexOf("transactions.execute"))
    }

    @Test
    fun `permission mapper xml keeps bounded queries and multiline writes`() {
        val data = source("src/main/resources/mybatis/mapper/auth/AuthDataPermissionMapper.xml")
        assertTrue(data.contains("LIMIT #{limit}"), "data permission query must remain bounded")
        assertFalse(data.contains("SELECT *"), "permission SQL must name fields")
        listOf("AuthUserRoleMapper.xml", "AuthRolePermissionMapper.xml", "AuthAdminDataPermissionMapper.xml").forEach { name ->
            val xml = source("src/main/resources/mybatis/mapper/auth/$name")
            assertFalse(Regex("INSERT INTO [^\\n]+\\([^\\n]+\\) VALUES").containsMatchIn(xml), "$name has compressed INSERT")
        }
    }

    @Test
    fun `login and permission implementation methods have adjacent KDoc`() {
        val functionDeclaration = Regex(
            "^\\s*(?:(?:public|private|protected|internal|open|final|suspend|inline|tailrec|operator|infix|override)\\s+)*fun\\s+",
        )
        val violations = permissionSourceFiles().flatMap { file ->
            val lines = Files.readAllLines(file)
            lines.mapIndexedNotNull { index, line ->
                if (functionDeclaration.containsMatchIn(line) && !hasAdjacentKDoc(lines, index)) {
                    "${projectDir.relativize(file)}:${index + 1} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "登录/权限实现方法必须有紧邻 KDoc，类注释或接口注释不能替代实现说明：\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `login and permission code keeps annotations and control flow readable`() {
        val annotationAndFunction = Regex("^\\s*@[A-Za-z][^\\n]*\\s+fun\\s+")
        val compressedIf = Regex(
            "^\\s*if\\s*\\(.*\\)\\s+(?:throw\\b|return\\b|require\\b|check\\b|error\\b|" +
                "invalid\\b|failed\\b|serviceFailure\\b|syncConflict\\b|" +
                "[A-Za-z_][A-Za-z0-9_]*\\+\\+|[A-Za-z_][A-Za-z0-9_]*\\s*=)",
        )
        val compressedElse = Regex("^\\s*else\\s+(?!if\\b|\\{)(?:throw\\b|return\\b|[A-Za-z_])")
        val violations = permissionSourceFiles().flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                when {
                    annotationAndFunction.containsMatchIn(line) ->
                        "${projectDir.relativize(file)}:${index + 1} -> annotation and fun must use separate lines"
                    compressedIf.containsMatchIn(line) || compressedElse.containsMatchIn(line) ->
                        "${projectDir.relativize(file)}:${index + 1} -> conditional body must use braces and separate lines"
                    else -> null
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "登录/权限链禁止注解与函数、条件与执行体压缩在同一行：\n${violations.joinToString("\n")}",
        )
    }

    /** 允许 KDoc 与方法之间只有空行或注解；普通块注释和远处的类注释均不算方法说明。 */
    private fun hasAdjacentKDoc(lines: List<String>, functionIndex: Int): Boolean {
        var cursor = functionIndex - 1
        while (cursor >= 0 && (lines[cursor].isBlank() || lines[cursor].trimStart().startsWith("@"))) cursor--
        if (cursor < 0 || !lines[cursor].trimEnd().endsWith("*/")) return false
        while (cursor >= 0) {
            val line = lines[cursor].trimStart()
            if (line.startsWith("/**")) return true
            if (line.startsWith("/*")) return false
            cursor--
        }
        return false
    }

    /** 合并登录/权限链已迁移目录与显式跨域文件，供同一验收矩阵复用。 */
    private fun permissionSourceFiles(): List<Path> = buildList {
        documentedPermissionRoots.forEach { root ->
            Files.walk(projectDir.resolve(root)).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .forEach { add(it) }
            }
        }
        documentedPermissionFiles.forEach { add(projectDir.resolve(it)) }
    }.distinct().sorted()

    /** 读取相对后端工程目录的源码，供文本型架构约束复用。 */
    private fun source(path: String): String = Files.readString(projectDir.resolve(path))
}
