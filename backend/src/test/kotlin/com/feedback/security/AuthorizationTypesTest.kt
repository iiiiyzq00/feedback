package com.feedback.security

import com.feedback.model.auth.AuthProvider
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 稳定编码解析和 Spring authority 边界的兼容测试。 */
class AuthorizationTypesTest {
    @Test
    fun `principal exposes unchanged Spring authorities from strong types`() {
        val principal = WebPrincipal(
            1,
            "admin",
            "hash",
            "Admin",
            setOf(RoleCode.ADMIN),
            setOf(PermissionCode.requireStorage("access:user_management")),
            authProvider = AuthProvider.SSO,
        )

        assertEquals(setOf("ROLE_ADMIN", "access:user_management"), principal.authorities.map { it.authority }.toSet())
    }

    @Test
    fun `unknown role provider and malformed permission fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { RoleCode.requireStorage("SUPER_ADMIN") }
        assertThrows(IllegalArgumentException::class.java) { AuthProvider.requireStorage("OAUTH") }
        assertThrows(IllegalArgumentException::class.java) { PermissionCode.requireStorage("access user") }
    }
}
