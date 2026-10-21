package com.feedback.security

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import java.util.function.Supplier

class AccessAuthorizationTest {
    private val access = AccessAuthorization()
    private val workspacePermission = setOf("access:phase_one_workspace")

    @Test
    fun `workspace requires its single permission and a Feishu identity`() {
        assertTrue(access.channel().check(auth("SSO", workspacePermission), mockk(relaxed = true))!!.isGranted)
        assertFalse(access.channel().check(auth("LOCAL", workspacePermission), mockk(relaxed = true))!!.isGranted)
        assertFalse(access.channel().check(auth("SSO", emptySet()), mockk(relaxed = true))!!.isGranted)
    }

    @Test
    fun `analysis requires Feishu identity and analysis permission`() {
        val required = workspacePermission
        assertTrue(access.analysis().check(auth("SSO", required), mockk(relaxed = true))!!.isGranted)
        assertFalse(access.analysis().check(auth("LOCAL", required), mockk(relaxed = true))!!.isGranted)
        assertFalse(access.analysis().check(auth("SSO", emptySet()), mockk(relaxed = true))!!.isGranted)
    }

    @Test
    fun `workspace permission includes governed export`() {
        val required = workspacePermission
        assertTrue(access.channelExport().check(auth("SSO", required), mockk(relaxed = true))!!.isGranted)
        assertFalse(access.channelExport().check(auth("SSO", emptySet()), mockk(relaxed = true))!!.isGranted)
    }

    @Test
    fun `phase one role cannot use APIs outside its workspace`() {
        val phasePrincipal = WebPrincipal(1, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"), workspacePermission, authProvider = "SSO")
        val adminPrincipal = WebPrincipal(2, "admin", "", "Admin", setOf("ADMIN"), workspacePermission, authProvider = "SSO")
        val phaseAuth = UsernamePasswordAuthenticationToken.authenticated(phasePrincipal, null, phasePrincipal.authorities)
        val adminAuth = UsernamePasswordAuthenticationToken.authenticated(adminPrincipal, null, adminPrincipal.authorities)
        val anonymous = UsernamePasswordAuthenticationToken.unauthenticated("anonymous", null)
        assertFalse(access.outsideWorkspace().check({ phaseAuth }, mockk(relaxed = true))!!.isGranted)
        assertTrue(access.outsideWorkspace().check({ adminAuth }, mockk(relaxed = true))!!.isGranted)
        assertFalse(access.outsideWorkspace().check({ anonymous }, mockk(relaxed = true))!!.isGranted)
    }

    private fun auth(provider: String, permissions: Set<String>): Supplier<Authentication> = Supplier {
        val principal = WebPrincipal(1, "user", "", "User", setOf("AUDITOR"), permissions, authProvider = provider)
        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
    }
}
