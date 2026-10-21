package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.feedback.repository.auth.AuthSessionRepository
import com.feedback.repository.auth.LoginAuditRepository
import com.feedback.service.impl.auth.LocalLoginSuccessServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 验证本地登录短写阶段的条件提交与成功审计原子编排。 */
class LocalLoginSuccessServiceTest {
    private val sessions = mockk<AuthSessionRepository>()
    private val audits = mockk<LoginAuditRepository>()
    private val service = LocalLoginSuccessServiceImpl(sessions, audits)

    @Test
    fun `current snapshot updates login time then records success audit`() {
        every { sessions.recordSuccessfulLocalLogin(7, 4) } returns true
        every { audits.recordSuccess(7, "auditor", "192.0.2.20") } returns Unit

        service.commit(7, "auditor", 4, "192.0.2.20")

        verifyOrder {
            sessions.recordSuccessfulLocalLogin(7, 4)
            audits.recordSuccess(7, "auditor", "192.0.2.20")
        }
    }

    @Test
    fun `changed snapshot cannot write audit or establish a session`() {
        every { sessions.recordSuccessfulLocalLogin(7, 4) } returns false

        assertThrows(AuthenticationSnapshotChangedException::class.java) {
            service.commit(7, "auditor", 4, "192.0.2.20")
        }

        verify(exactly = 0) { audits.recordSuccess(any(), any(), any()) }
    }
}
