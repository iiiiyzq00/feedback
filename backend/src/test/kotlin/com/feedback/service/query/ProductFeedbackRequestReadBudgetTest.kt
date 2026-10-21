package com.feedback.service.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.query.ProductFeedbackRequestPo
import com.feedback.repository.query.ProductFeedbackRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class ProductFeedbackRequestReadBudgetTest {
    private val repository = mockk<ProductFeedbackRepository>()
    private val service = ProductFeedbackRequestService(repository, mockk<ObjectMapper>(), mockk<DataPermissionService>())
    private val row = ProductFeedbackRequestPo(id = 7, requestNo = "REQ-7", createdBy = 9)

    @BeforeEach
    fun authenticate() {
        val principal = WebPrincipal(9, "tester", "", "测试用户", setOf("ADMIN"), emptySet(), authProvider = "SSO")
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `online list uses count page and one batched user lookup`() {
        every { repository.count(null, null, null) } returns 1
        every { repository.page(null, null, null, 100, 0) } returns listOf(row)
        every { repository.names(listOf(9L)) } returns mapOf(9L to "测试用户")

        val result = service.list(null, null, false, 1, 100)

        assertEquals(1L, result["total"])
        verify(exactly = 1) { repository.count(null, null, null) }
        verify(exactly = 1) { repository.page(null, null, null, 100, 0) }
        verify(exactly = 1) { repository.names(listOf(9L)) }
    }

    @Test
    fun `online detail uses one unlocked request read and batched event actors`() {
        every { repository.find(7) } returns row
        every { repository.events(7) } returns emptyList()
        every { repository.names(emptyList()) } returns emptyMap()

        val result = service.detail(7)

        assertEquals(7L, result["id"])
        verify(exactly = 1) { repository.find(7) }
        verify(exactly = 1) { repository.events(7) }
        verify(exactly = 1) { repository.names(emptyList()) }
        verify(exactly = 0) { repository.lock(any()) }
    }
}
