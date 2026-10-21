package com.feedback.repository.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.repository.impl.auth.MyBatisLoginAuditRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** 验证登录审计不会把可枚举的账号明文写入数据库。 */
class LoginAuditRepositoryTest {
    @Test
    fun `blocked audit stores only username fingerprint`() {
        val mutations = mockk<SystemAuditMutationRepository>()
        val inserted = slot<SystemAuditInsertPo>()
        every { mutations.append(capture(inserted)) } returns Unit

        MyBatisLoginAuditRepositoryImpl(mutations, jacksonObjectMapper()).recordBlocked(
            "Student.Name",
            "192.0.2.10",
            accountLimited = true,
            ipLimited = false,
            windowSeconds = 900,
        )

        assertEquals("LOGIN_BLOCKED", inserted.captured.operation)
        assertFalse(inserted.captured.operationDetailJson.contains("Student.Name"))
        assertFalse(inserted.captured.targetId.contains("Student.Name"))
    }
}
