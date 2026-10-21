package com.feedback.service.knowledge

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.knowledge.JiraKnowledgeAuditMapper
import com.feedback.mapper.knowledge.JiraKnowledgeContentMapper
import com.feedback.mapper.knowledge.JiraKnowledgeDraftMapper
import com.feedback.mapper.knowledge.JiraKnowledgeJiraMapper
import com.feedback.mapper.knowledge.JiraKnowledgeSourceMapper
import com.feedback.mapper.knowledge.KnowledgeClosureAuditMapper
import com.feedback.mapper.knowledge.KnowledgeClosureIncidentMapper
import com.feedback.mapper.knowledge.KnowledgeClosureKnowledgeMapper
import com.feedback.repository.knowledge.JiraKnowledgeCandidateRepository
import com.feedback.repository.knowledge.KnowledgeClosureRepository
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class KnowledgeExceptionContractTest {
    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `candidate generation requires authentication through the unified contract`() {
        val service = JiraKnowledgeCandidateService(mockk(), mockk())

        val error = assertThrows(ClientException::class.java) {
            service.generate(JiraKnowledgeCandidateRequest())
        }

        assertEquals(ErrorCode.AUTH_UNAUTHORIZED, error.errorCode)
        assertEquals("AUTHENTICATION_REQUIRED", error.reasonCode)
    }

    @Test
    fun `candidate generation validates its limit through the unified contract`() {
        authenticate(setOf("action:generate_jira_knowledge_candidates"))
        val service = JiraKnowledgeCandidateService(mockk(), mockk())

        val error = assertThrows(ClientException::class.java) {
            service.generate(JiraKnowledgeCandidateRequest(limit = 0))
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("JIRA_KNOWLEDGE_CANDIDATE_LIMIT_INVALID", error.reasonCode)
    }

    @Test
    fun `missing knowledge statistics use the unified not found contract`() {
        val knowledge = mockk<KnowledgeClosureKnowledgeMapper>()
        every { knowledge.findStatistics(7) } returns null
        val repository = KnowledgeClosureRepository(knowledge, mockk(), mockk())

        val error = assertThrows(ClientException::class.java) { repository.statistics(7) }

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
        assertEquals("KNOWLEDGE_NOT_FOUND", error.reasonCode)
    }

    @Test
    fun `candidate audit write failures use the unified service contract`() {
        val audits = mockk<JiraKnowledgeAuditMapper>()
        every { audits.insert(9, "GENERATE", "{}") } returns 0
        val repository = JiraKnowledgeCandidateRepository(
            mockk<JiraKnowledgeSourceMapper>(),
            mockk<JiraKnowledgeContentMapper>(),
            mockk<JiraKnowledgeJiraMapper>(),
            mockk<JiraKnowledgeDraftMapper>(),
            audits
        )

        val error = assertThrows(ServiceException::class.java) { repository.audit(9, "GENERATE", "{}") }

        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("JIRA_KNOWLEDGE_AUDIT_WRITE_FAILED", error.reasonCode)
    }

    private fun authenticate(permissions: Set<String>) {
        val principal = WebPrincipal(9, "tester", "", "测试用户", setOf("ADMIN"), permissions)
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, emptyList())
    }
}
