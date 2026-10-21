package com.feedback.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 登录域分层门禁。
 *
 * 防止 Controller 重新依赖实现、Service 接口携带 Spring 事务注解、实现直连 Mapper，
 * 或复杂 RepositoryImpl 反向依赖内部 Service；路径清单仅覆盖已完成迁移的登录主链。
 */
class AuthLayeringArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    @Test
    fun `auth controller depends only on service contracts`() {
        val source = source("src/main/kotlin/com/feedback/controller/auth/AuthController.kt")
        assertFalse(source.contains("import com.feedback.service.impl."), "AuthController must not import ServiceImpl")
    }

    @Test
    fun `auth service contracts remain free of spring implementation annotations`() {
        listOf(
            "src/main/kotlin/com/feedback/service/auth/AuthSessionLifecycleService.kt",
            "src/main/kotlin/com/feedback/service/auth/FeishuSsoService.kt",
            "src/main/kotlin/com/feedback/service/auth/LocalAuthenticationService.kt",
            "src/main/kotlin/com/feedback/service/auth/LocalLoginSuccessService.kt",
            "src/main/kotlin/com/feedback/service/auth/FeishuRoleMappingService.kt",
            "src/main/kotlin/com/feedback/service/auth/FeishuAuthorizationPolicyService.kt",
            "src/main/kotlin/com/feedback/service/admin/AdminManagementService.kt",
        ).forEach { file ->
            val source = source(file)
            assertFalse(source.contains("@Service"), "$file must remain a pure service contract")
            assertFalse(source.contains("@Transactional"), "$file must not own transaction implementation")
        }
    }

    @Test
    fun `auth service implementations follow naming and mapper boundaries`() {
        mapOf(
            "src/main/kotlin/com/feedback/service/impl/auth/AuthSessionLifecycleServiceImpl.kt" to
                "AuthSessionLifecycleService",
            "src/main/kotlin/com/feedback/service/impl/auth/FeishuSsoServiceImpl.kt" to
                "FeishuSsoService",
            "src/main/kotlin/com/feedback/service/impl/auth/LocalAuthenticationServiceImpl.kt" to
                "LocalAuthenticationService",
            "src/main/kotlin/com/feedback/service/impl/auth/LocalLoginSuccessServiceImpl.kt" to
                "LocalLoginSuccessService",
            "src/main/kotlin/com/feedback/service/impl/auth/DataPermissionServiceImpl.kt" to
                "DataPermissionService",
            "src/main/kotlin/com/feedback/service/impl/auth/FeishuRoleMappingServiceImpl.kt" to
                "FeishuRoleMappingService",
            "src/main/kotlin/com/feedback/service/impl/auth/FeishuAuthorizationPolicyServiceImpl.kt" to
                "FeishuAuthorizationPolicyService",
            "src/main/kotlin/com/feedback/service/impl/admin/AdminManagementServiceImpl.kt" to
                "AdminManagementService",
        ).forEach { (file, serviceContract) ->
            val source = source(file)
            val implementationName = Path.of(file).fileName.toString().removeSuffix(".kt")
            assertTrue(implementationName.endsWith("ServiceImpl"), "$file must use the ServiceImpl suffix")
            assertTrue(source.contains(": $serviceContract"), "$file must implement $serviceContract")
            assertFalse(source.contains("import com.feedback.mapper."), "$file must not depend on Mapper")
        }
    }

    @Test
    fun `auth repository implementation does not depend on internal services`() {
        val file = "src/main/kotlin/com/feedback/repository/impl/auth/MyBatisFeishuSsoRepositoryImpl.kt"
        val source = source(file)
        assertFalse(source.contains("import com.feedback.service."), "$file must not call internal Service")
    }

    private fun source(relativePath: String): String = Files.readString(projectDir.resolve(relativePath))
}
