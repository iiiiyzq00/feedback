package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.WebPrincipal
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.repository.auth.*
import com.feedback.service.impl.auth.FeishuRoleMappingServiceImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class FeishuRoleMappingServiceTest {
    private val mappings = mockk<FeishuRoleMappingRepository>()
    private val audits = mockk<SystemAuditMutationRepository>(relaxed = true)
    private val service = FeishuRoleMappingServiceImpl(mappings, mockk(), audits, jacksonObjectMapper())

    init {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            WebPrincipal(21, "owner", "", "项目负责人", setOf("ADMIN"), setOf("access:user_management")), null, emptyList()
        )
    }

    @AfterEach fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `new mapping is always stored as draft and audited`() {
        val stored=io.mockk.slot<AuthFeishuRoleMappingPo>();every { mappings.create(capture(stored)) } returns 7L

        val result = service.create(FeishuRoleMappingInput("USER_GROUP", "group-1", "反馈运营组", "OPERATOR", 20, "负责反馈处置"))

        assertEquals("DRAFT", result["decisionStatus"])
        assertEquals("OPERATOR",stored.captured.roleCode)
        verify { audits.append(any()) }
    }

    @Test
    fun `default mapping normalizes subject and rejects unknown roles`() {
        val stored=io.mockk.slot<AuthFeishuRoleMappingPo>();every { mappings.create(capture(stored)) } returns 8L
        service.create(FeishuRoleMappingInput("DEFAULT", "ignored", "默认员工", "USER"))
        assertEquals("*",stored.captured.subjectId)

        val error = assertThrows(ClientException::class.java) {
            service.create(FeishuRoleMappingInput("USER_GROUP", "group-2", "未知角色组", "SUPER_ADMIN"))
        }
        assertEquals(ErrorCode.BAD_REQUEST,error.errorCode)
        assertEquals("FEISHU_MAPPING_ROLE_INVALID",error.reasonCode)
    }

    @Test
    fun `approval requires a reason and optimistic version`() {
        val error = assertThrows(ClientException::class.java) {
            service.decide(7, FeishuRoleMappingDecisionInput("APPROVED", "短", 1))
        }
        assertEquals(ErrorCode.BAD_REQUEST,error.errorCode)
        assertEquals("FEISHU_MAPPING_DECISION_REASON_REQUIRED",error.reasonCode)
        every { mappings.decide(7,"APPROVED","由项目负责人确认",21,1,"DRAFT") } returns 1
        val result = service.decide(7, FeishuRoleMappingDecisionInput("APPROVED", "由项目负责人确认", 1))
        assertEquals("APPROVED", result["decisionStatus"])
        assertEquals(2L, result["version"])
    }
}
