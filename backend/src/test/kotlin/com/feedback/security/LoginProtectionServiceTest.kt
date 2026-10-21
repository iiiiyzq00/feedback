package com.feedback.security

import com.feedback.common.exception.ClientException
import com.feedback.repository.auth.LoginAuditRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class LoginProtectionServiceTest {
    private val store = mockk<LoginAttemptStore>()
    private val audits = mockk<LoginAuditRepository>()
    private val service = LoginProtectionService(store, audits, 5, 20, 900)

    @Test
    fun `allows login below both account and IP thresholds`() {
        every { store.count(any()) } returnsMany listOf(4, 19)
        service.assertAllowed("student", "192.0.2.10")
    }

    @Test
    fun `blocks at account threshold and writes audit`() {
        every { store.count(any()) } returnsMany listOf(5, 3)
        every { audits.recordBlocked(any(), any(), any(), any(), any()) } returns Unit

        val error = assertThrows(ClientException::class.java) {
            service.assertAllowed("Student.Name", "192.0.2.10")
        }

        assertEquals("AUTH_LOGIN_RATE_LIMITED", error.errorCode.name)
        verify { audits.recordBlocked("Student.Name", "192.0.2.10", true, false, 900) }
    }

    @Test
    fun `failed login increments account and IP and audits generic reason`() {
        every { store.increment(any(), Duration.ofSeconds(900)) } returnsMany listOf(2, 7)
        every { audits.recordFailure(any(), any(), any(), any(), any()) } returns Unit

        service.recordFailure("unknown-user", "192.0.2.11")

        verify(exactly = 2) { store.increment(any(), Duration.ofSeconds(900)) }
        verify { audits.recordFailure("unknown-user", "192.0.2.11", 2, 7, 900) }
    }

    @Test
    fun `successful login clears account failures but preserves shared IP counter`() {
        every { store.reset(any()) } returns Unit

        service.resetAfterSuccess("student")

        verify(exactly = 1) { store.reset(match { it.startsWith("auth:login:account:") }) }
        verify(exactly = 0) { store.reset(match { it.startsWith("auth:login:ip:") }) }
    }
}
