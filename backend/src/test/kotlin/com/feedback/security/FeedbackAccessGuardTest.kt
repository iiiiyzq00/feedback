package com.feedback.security

import com.feedback.model.auth.*
import com.feedback.service.auth.DataPermissionService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.repository.feedback.access.FeedbackAccessFacts
import com.feedback.repository.feedback.access.FeedbackAccessRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackAccessGuardTest {
    private val repository = mockk<FeedbackAccessRepository>()
    private val permissions = mockk<DataPermissionService>()
    private val principalProvider = mockk<CurrentWebPrincipalProvider>()
    private val guard = FeedbackAccessGuard(repository, permissions, principalProvider)

    init {
        every { principalProvider.require() } returns WebPrincipal(
            9, "phase", "", "反馈用户", setOf("PHASE_ONE_PRODUCT"),
            setOf("access:phase_one_workspace"), authProvider = "SSO"
        )
    }

    @Test
    fun `workspace rejection happens before source existence lookup`() {
        every { principalProvider.require() } returns WebPrincipal(
            9, "local", "", "本地用户", setOf("OPERATOR"), emptySet(), authProvider = "LOCAL"
        )

        val error = assertThrows(ClientException::class.java) { guard.require(11) }

        assertEquals(ErrorCode.AUTH_FORBIDDEN, error.errorCode)
        assertEquals("FEEDBACK_WORKSPACE_PERMISSION_REQUIRED", error.reasonCode)

        verify(exactly = 0) { repository.findBySourceId(any()) }
        verify(exactly = 0) { permissions.scope() }
    }

    @Test
    fun `missing or unsafe source facts use the same not found error`() {
        val rejected = listOf(
            null,
            facts().copy(dataEnvironment = "TEST"),
            facts().copy(sourceChannel = "UNKNOWN"),
            facts().copy(deleted = true),
            facts().copy(maskingStatus = "REVIEW_NEEDED")
        )

        rejected.forEach { value ->
            every { repository.findBySourceId(11) } returns value
            val error = assertThrows(ClientException::class.java) { guard.require(11) }
            assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
            assertEquals("FEEDBACK_NOT_FOUND", error.reasonCode)
        }
        verify(exactly = 0) { permissions.scope() }
    }

    /**
     * 跨租户必须与「记录不存在」完全同一个响应，理由见 [FeedbackAccessGuard] 的类注释：
     * 报「拒绝」本身就是一个可用于探测「这个 ID 在别的租户里存在」的信道。
     *
     * 这条在守卫这一层就能钉住，所以不必等到连真库的门控用例去发现。
     */
    @Test
    fun `cross tenant source is indistinguishable from a missing record`() {
        every { repository.findBySourceId(11) } returns facts(tenant = "TENANT_B")
        every { permissions.scope() } returns DataAccessScope(setOf("JIRA"), null, null, tenants = setOf("TENANT_A"))

        val error = assertThrows(ClientException::class.java) { guard.require(11) }

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
        assertEquals("FEEDBACK_NOT_FOUND", error.reasonCode)
    }

    @Test
    fun `scope rejection keeps the stable data scope error`() {
        every { repository.findBySourceId(11) } returns facts()
        every { permissions.scope() } returns DataAccessScope(setOf("QUALITY"), null, null)

        val error = assertThrows(ClientException::class.java) { guard.require(11) }

        assertEquals(ErrorCode.AUTH_DATA_PERMISSION_DENIED, error.errorCode)
        assertEquals("DATA_SCOPE_ACCESS_DENIED", error.reasonCode)
    }

    @Test
    fun `successful check returns the exact facts and scope snapshots`() {
        val facts = facts()
        val scope = DataAccessScope(setOf("JIRA"), setOf("COURSE"), setOf("GD"))
        every { repository.findBySourceId(11) } returns facts
        every { permissions.scope() } returns scope

        val result = guard.require(11)

        assertSame(facts, result.facts)
        assertSame(scope, result.scope)
    }

    private fun facts(tenant: String = TenantKeys.DEFAULT) = FeedbackAccessFacts(
        11, "JIRA", "PRODUCTION", "COURSE", "GD", "SZ", "PASS", false, tenant = tenant
    )
}
