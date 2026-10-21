package com.feedback.architecture

import com.feedback.repository.impl.auth.MyBatisAuthSessionRepositoryImpl
import com.feedback.repository.impl.auth.MyBatisFeishuSsoRepositoryImpl
import com.feedback.repository.impl.auth.MyBatisLoginAuditRepositoryImpl
import com.feedback.security.LoginProtectionService
import com.feedback.security.WebUserDetailsService
import com.feedback.service.impl.auth.AuthSessionLifecycleServiceImpl
import com.feedback.service.impl.auth.FeishuSsoServiceImpl
import com.feedback.service.impl.auth.LocalAuthenticationServiceImpl
import com.feedback.service.impl.auth.LocalLoginSuccessServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/**
 * 登录域事务边界门禁。
 *
 * 边界依据一次本地业务阶段的数据库交互次数和一致性要求，而不是依据单表或多表：多次相关
 * 查询/写入必须有事务，单条原子 SQL 不额外开启事务。BCrypt 等高成本计算以及外部 HTTP、
 * Redis、Cookie I/O 不纳入数据库事务；事务外计算后的写入必须通过版本条件确认快照仍有效。
 * 该清单覆盖已迁移登录域，具体并发消费行为由 SSO 与 MyBatis 契约测试覆盖。
 */
class AuthTransactionBoundaryTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    @Test
    fun `single statement session operations remain non transactional`() {
        assertNotTransactional(AuthSessionLifecycleServiceImpl::class.java, "invalidate")
        assertNotTransactional(MyBatisAuthSessionRepositoryImpl::class.java, "recordSuccessfulLocalLogin")
        assertNotTransactional(MyBatisAuthSessionRepositoryImpl::class.java, "invalidateAllSessions")
        assertNotTransactional(MyBatisFeishuSsoRepositoryImpl::class.java, "createState")
        assertNotTransactional(MyBatisLoginAuditRepositoryImpl::class.java, "recordBlocked")
        assertNotTransactional(MyBatisLoginAuditRepositoryImpl::class.java, "recordFailure")
    }

    @Test
    fun `multi statement SSO operations retain explicit transaction owners`() {
        assertTransactional(FeishuSsoServiceImpl::class.java, "exchangeLoginCode")
        assertTransactional(MyBatisFeishuSsoRepositoryImpl::class.java, "completeCallback")
        assertNotTransactional(MyBatisFeishuSsoRepositoryImpl::class.java, "consumeLoginCode")
    }

    @Test
    fun `remote and Redis stages remain outside database transactions`() {
        assertNotTransactional(FeishuSsoServiceImpl::class.java, "authorize")
        assertNotTransactional(FeishuSsoServiceImpl::class.java, "callback")
        assertNotTransactional(LoginProtectionService::class.java, "assertAllowed")
        assertNotTransactional(LoginProtectionService::class.java, "recordFailure")
        assertNotTransactional(LoginProtectionService::class.java, "resetAfterSuccess")
    }

    @Test
    fun `local authentication keeps password verification outside the short write transaction`() {
        assertNotTransactional(LocalAuthenticationServiceImpl::class.java, "authenticate")
        assertTransactional(LocalLoginSuccessServiceImpl::class.java, "commit")
        assertNotTransactional(MyBatisLoginAuditRepositoryImpl::class.java, "recordSuccess")
    }

    @Test
    fun `local login write rejects a stale authentication snapshot`() {
        val mapperXml = Files.readString(
            projectDir.resolve("src/main/resources/mybatis/mapper/auth/AuthUserMapper.xml"),
        )
        val statement = mapperXml.substringAfter("<update id=\"touchLastLoginIfAuthenticationCurrent\">")
            .substringBefore("</update>")
        assertTrue(statement.contains("AND is_active = 1"), "local login commit must recheck active state")
        assertTrue(statement.contains("AND auth_provider = 'LOCAL'"), "local login commit must reject SSO accounts")
        assertTrue(
            statement.contains("AND auth_version = #{expectedAuthVersion}"),
            "local login commit must reject stale authorization snapshots",
        )
    }

    @Test
    fun `principal assembly uses a consistent read only snapshot`() {
        assertReadOnlyTransactional(WebUserDetailsService::class.java, "loadUserByUsername")
        assertReadOnlyTransactional(WebUserDetailsService::class.java, "loadUserById")
    }

    private fun assertTransactional(type: Class<*>, methodName: String) {
        val method = declaredMethod(type, methodName)
        assertTrue(method.isAnnotationPresent(Transactional::class.java), "${type.simpleName}.$methodName must own a transaction")
    }

    private fun assertNotTransactional(type: Class<*>, methodName: String) {
        val method = declaredMethod(type, methodName)
        assertFalse(type.isAnnotationPresent(Transactional::class.java), "${type.simpleName} must not create a class-wide transaction")
        assertFalse(method.isAnnotationPresent(Transactional::class.java), "${type.simpleName}.$methodName must not create a transaction")
    }

    private fun assertReadOnlyTransactional(type: Class<*>, methodName: String) {
        val method = declaredMethod(type, methodName)
        val annotation = method.getAnnotation(Transactional::class.java)
        assertTrue(annotation != null, "${type.simpleName}.$methodName must own a transaction")
        assertEquals(true, annotation?.readOnly, "${type.simpleName}.$methodName must be read only")
    }

    /** Kotlin 为协变返回值生成 bridge 方法时，只检查承载源码注解的实际声明。 */
    private fun declaredMethod(type: Class<*>, methodName: String) =
        type.declaredMethods.single { it.name == methodName && !it.isBridge }
}
