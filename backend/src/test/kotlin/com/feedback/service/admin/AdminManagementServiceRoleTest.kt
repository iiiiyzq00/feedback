package com.feedback.service.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.security.WebPrincipal
import com.feedback.model.po.auth.AuthExternalIdentityPo
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.*
import com.feedback.model.auth.RoleCode
import com.feedback.service.impl.admin.AdminManagementServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder

class AdminManagementServiceRoleTest {
    private val users = mockk<AuthUserRepository>()
    private val grants = mockk<AuthGrantRepository>()
    private val identities = mockk<AuthExternalIdentityRepository>()
    private val audits = mockk<SystemAuditMutationRepository>(relaxed = true)
    private val roleCatalog = mockk<AuthRoleCatalogRepository>()
    private val scopes = mockk<AuthAdminDataPermissionRepository>()
    private val service = AdminManagementServiceImpl(jacksonObjectMapper(), mockk<PasswordEncoder>(), users, grants,
        identities, roleCatalog, scopes, audits, mockk<TenantRegistryRepository>(), mockk())

    @BeforeEach
    fun authenticateAdmin() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            WebPrincipal(21, "owner", "", "杨紫琦", setOf("ADMIN"), setOf("access:user_management"), authProvider = "SSO"),
            null,
            emptyList()
        )
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `admin assigns the exclusive phase one role to a bound Feishu user`() {
        every { grants.lockRoleMutationFence() } returns RoleCode.entries.toSet()
        every { users.lockById(42) } returns AuthUserPo(42,"feishu_user","用户",authProvider="SSO",active=true)
        every { identities.feishuByUserIds(setOf(42)) } returns listOf(AuthExternalIdentityPo(userId=42,providerSubject="ou_42"))
        every { grants.roles(42) } returns setOf(RoleCode.USER)
        every { grants.replace(42,setOf(RoleCode.PHASE_ONE_PRODUCT),21) } returns Unit
        every { users.invalidateSessions(42) } returns Unit

        val result = service.assignRoles(42, setOf("PHASE_ONE_PRODUCT"))

        assertEquals(listOf("PHASE_ONE_PRODUCT"), result["roles"])
        verify { grants.replace(42,setOf(RoleCode.PHASE_ONE_PRODUCT),21) }
        verify { audits.append(match { it.operation=="USER_ROLES_ASSIGNED"&&it.userId==21L&&it.targetId=="42" }) }
    }

    @Test
    fun `phase one role rejects users without an active Feishu binding`() {
        every { grants.lockRoleMutationFence() } returns RoleCode.entries.toSet()
        every { users.lockById(43) } returns AuthUserPo(43,"local_user","用户",authProvider="LOCAL",active=true)
        every { grants.roles(43) } returns setOf(RoleCode.USER)

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRoles(43, setOf("PHASE_ONE_TECHNICAL"))
        }
        verify(exactly = 0) { grants.replace(any(),any<Set<RoleCode>>(),any()) }
    }

    @Test
    fun `phase one role cannot be combined or have its permission package changed`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.assignRoles(44, setOf("PHASE_ONE_TECHNICAL", "PHASE_ONE_PRODUCT"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceRolePermissions("PHASE_ONE_PRODUCT", setOf("access:phase_one_workspace", "access:user_management"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.replaceRolePermissions("PHASE_ONE_TECHNICAL", setOf("access:phase_one_workspace"))
        }
    }

    @Test
    fun `data permission replacement invalidates the same user session and audits`() {
        every { users.lockById(42) } returns AuthUserPo(42, "feishu_user", "用户", authProvider = "SSO", active = true)
        every { scopes.replace(42, any()) } returns Unit
        every { users.invalidateSessions(42) } returns Unit

        val result = service.replaceDataPermissions(
            42,
            listOf(DataScope("channel", "sdk", "read", "allow")),
        )

        assertEquals(1, result["scopes"])
        verify { users.invalidateSessions(42) }
        verify { audits.append(match { it.operation == "USER_DATA_PERMISSIONS_REPLACED" && it.targetId == "42" }) }
    }

    @Test
    fun `last active admin removal is rejected while holding role then user locks`() {
        every { grants.lockRoleMutationFence() } returns RoleCode.entries.toSet()
        every { users.lockById(42) } returns AuthUserPo(42, "admin_42", "管理员", authProvider = "LOCAL", active = true)
        every { grants.roles(42) } returns setOf(RoleCode.ADMIN)
        every { grants.activeUserCount(RoleCode.ADMIN) } returns 1

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRoles(42, setOf("USER"))
        }

        verifyOrder {
            grants.lockRoleMutationFence()
            users.lockById(42)
            grants.roles(42)
            grants.activeUserCount(RoleCode.ADMIN)
        }
        verify(exactly = 0) { grants.replace(any(), any<Set<RoleCode>>(), any()) }
    }
}
