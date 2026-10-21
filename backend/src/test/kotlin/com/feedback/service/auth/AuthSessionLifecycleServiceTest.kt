package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.feedback.repository.auth.AuthSessionRepository
import com.feedback.service.impl.auth.AuthSessionLifecycleServiceImpl
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test

/** 验证会话生命周期实现仅委托一条原子 SQL，不额外扩大事务范围。 */
class AuthSessionLifecycleServiceTest {
    private val sessions = mockk<AuthSessionRepository>()
    private val service = AuthSessionLifecycleServiceImpl(sessions)

    @Test
    fun `logout invalidates all existing tokens through repository`() {
        every { sessions.invalidateAllSessions(21) } just runs

        service.invalidate(21)

        verify(exactly = 1) { sessions.invalidateAllSessions(21) }
    }
}
