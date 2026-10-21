package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.feedback.common.exception.ServiceException
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PermissionCode
import com.feedback.model.auth.RoleCode
import com.feedback.service.impl.auth.LocalAuthenticationServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

/** 冻结“读取/验密在前、短写提交在后”的本地登录编排。 */
class LocalAuthenticationServiceTest {
    private val manager = mockk<AuthenticationManager>()
    private val success = mockk<LocalLoginSuccessService>()
    private val principal = WebPrincipal(
        userId = 7,
        loginName = "auditor",
        passwordHash = "hash",
        displayName = "Auditor",
        roles = setOf("AUDITOR"),
        permissions = setOf("feedback:read"),
        authVersion = 4,
    )

    @Test
    fun `successful authentication commits current snapshot before returning it`() {
        every { manager.authenticate(any()) } returns authenticated(principal)
        every { success.commit(7, "auditor", 4, "192.0.2.20") } returns Unit

        val result = LocalAuthenticationServiceImpl(manager, success)
            .authenticate("auditor", "password", "192.0.2.20")

        assertEquals(7, result.userId)
        assertEquals(4, result.authVersion)
        assertEquals(setOf(PermissionCode.requireStorage("feedback:read")), result.permissions)
        verifyOrder {
            manager.authenticate(any())
            success.commit(7, "auditor", 4, "192.0.2.20")
        }
    }

    @Test
    fun `snapshot conflict reloads and retries only once`() {
        val refreshed = principal.copy(authVersion = 5, roles = setOf(RoleCode.USER))
        every { manager.authenticate(any()) } returnsMany listOf(authenticated(principal), authenticated(refreshed))
        every { success.commit(7, "auditor", 4, any()) } throws AuthenticationSnapshotChangedException()
        every { success.commit(7, "auditor", 5, any()) } returns Unit

        val result = LocalAuthenticationServiceImpl(manager, success)
            .authenticate("auditor", "password", "192.0.2.20")

        assertEquals(5, result.authVersion)
        assertEquals(setOf(RoleCode.USER), result.roles)
        verify(exactly = 2) { manager.authenticate(any()) }
    }

    @Test
    fun `repeated snapshot conflicts return retryable service failure instead of bad credential`() {
        every { manager.authenticate(any()) } returns authenticated(principal)
        every { success.commit(any(), any(), any(), any()) } throws AuthenticationSnapshotChangedException()

        val error = assertThrows(ServiceException::class.java) {
            LocalAuthenticationServiceImpl(manager, success)
                .authenticate("auditor", "password", "192.0.2.20")
        }

        assertEquals("AUTHENTICATION_SNAPSHOT_RETRY_EXHAUSTED", error.reasonCode)
        verify(exactly = 2) { manager.authenticate(any()) }
    }

    private fun authenticated(value: WebPrincipal) = UsernamePasswordAuthenticationToken(
        value,
        null,
        value.authorities,
    )
}
