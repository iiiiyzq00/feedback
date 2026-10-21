package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.FeishuSsoProperties
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.auth.AuthExternalIdentityPo
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.repository.auth.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.FeishuSyncStatus
import com.feedback.service.impl.auth.FeishuAuthorizationPolicyServiceImpl

class FeishuAuthorizationPolicyServiceTest {
    private val mappings=mockk<FeishuRoleMappingRepository>()
    private val service=FeishuAuthorizationPolicyServiceImpl(mockk(),mappings,mockk(),mockk(),mockk(),mockk(),jacksonObjectMapper(),FeishuSsoProperties(),mockk(),mockk<TransactionTemplate>())

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `user group chooses role while department adds data scope`() {
        every { mappings.approved() } returns listOf(
            AuthFeishuRoleMappingPo(1,"DEFAULT","*","默认","ROLE","USER",null,null,100,description=null,createdBy=21),
            AuthFeishuRoleMappingPo(2,"USER_GROUP","group-ops","运营组","ROLE","OPERATOR",null,null,20,description=null,createdBy=21),
            AuthFeishuRoleMappingPo(3,"DEPARTMENT","dept-sdk","SDK部门","DATA_SCOPE",null,"CHANNEL","SDK",30,description=null,createdBy=21)
        )
        val result=service.preview(FeishuPolicyPreviewInput(groupIds=setOf("group-ops"),departmentIds=setOf("dept-sdk")))
        assertEquals("OPERATOR",result["roleCode"])
        assertEquals(1,(result["dataScopes"] as List<*>).size)
        assertEquals(3,result["matchedRuleCount"])
    }

    @Test
    fun `unmatched user remains least privilege`() {
        every { mappings.approved() } returns emptyList()
        val result=service.preview(FeishuPolicyPreviewInput())
        assertEquals("USER",result["roleCode"])
        assertEquals(0,result["matchedRuleCount"])
    }

    @Test
    fun `changed authorization increments auth version and audits in the user transaction`() {
        val policies = mockk<FeishuPolicyRepository>()
        val identities = mockk<AuthExternalIdentityRepository>()
        val users = mockk<AuthUserRepository>()
        val grants = mockk<AuthGrantRepository>()
        val audits = mockk<SystemAuditMutationRepository>()
        val organization = mockk<FeishuOrganizationClient>()
        authenticate()
        every { policies.startExclusive(21, any()) } returns 93
        every { identities.allFeishu() } returns listOf(AuthExternalIdentityPo(userId = 7, providerSubject = "ou_7"))
        every { organization.facts("ou_7") } returns FeishuOrganizationFacts(emptySet(), emptySet(), true)
        every { mappings.approved() } returns emptyList()
        every { grants.lockRoleMutationFence() } returns RoleCode.entries.toSet()
        every { grants.roles(7) } returns setOf(RoleCode.AUDITOR)
        every { policies.scopes(7) } returns emptySet()
        every { users.lockById(7) } returns AuthUserPo(id = 7, username = "user", displayName = "User", authProvider = "SSO")
        every { grants.replace(7, RoleCode.USER) } returns Unit
        every { policies.replaceScopes(7, emptySet()) } returns Unit
        every { users.updateSsoActiveAndVersion(7, true) } returns Unit
        every { audits.append(any()) } returns Unit
        every { policies.saveFacts(7, "[]", "[]", true) } returns Unit
        every { policies.finish(93, FeishuSyncStatus.COMPLETED, 1, 1, 0, 0, null) } returns Unit
        val subject = FeishuAuthorizationPolicyServiceImpl(
            policies, mappings, identities, users, grants, audits, jacksonObjectMapper(), enabledProperties(),
            organization, immediateTransactions(),
        )

        val result = subject.syncAll()

        assertEquals(1, result["changedUsers"])
        verify { users.updateSsoActiveAndVersion(7, true) }
        verify { audits.append(match { it.operation == "FEISHU_USER_AUTHORIZATION_SYNCHRONIZED" && it.targetId == "7" }) }
    }

    @Test
    fun `transient user transaction retries without repeating the Feishu request`() {
        val policies = mockk<FeishuPolicyRepository>()
        val identities = mockk<AuthExternalIdentityRepository>()
        val users = mockk<AuthUserRepository>()
        val grants = mockk<AuthGrantRepository>()
        val organization = mockk<FeishuOrganizationClient>()
        val retryMappings = mockk<FeishuRoleMappingRepository>()
        val properties = enabledProperties(retryAttempts = 2)
        authenticate()
        every { policies.startExclusive(21, any()) } returns 91
        every { identities.allFeishu() } returns listOf(
            AuthExternalIdentityPo(id = 1, userId = 7, providerSubject = "ou_p0")
        )
        every { organization.facts("ou_p0") } returns FeishuOrganizationFacts(setOf("g1"), setOf("d1"), true)
        every { retryMappings.approved() } returns listOf(
            AuthFeishuRoleMappingPo(1, "DEFAULT", "*", "默认", "ROLE", "USER", null, null, 100, description = null, createdBy = 21)
        )
        every { grants.lockRoleMutationFence() } returns RoleCode.entries.toSet()
        every { grants.roles(7) } returns setOf(RoleCode.USER)
        every { policies.scopes(7) } returns emptySet()
        every { users.lockById(7) } returns AuthUserPo(id = 7, username = "user", displayName = "User", authProvider = "SSO")
        var writes = 0
        every { policies.saveFacts(7, "[\"g1\"]", "[\"d1\"]", true) } answers {
            if (writes++ == 0) throw CannotAcquireLockException("deadlock")
        }
        every { policies.finish(91, FeishuSyncStatus.COMPLETED, 1, 0, 0, 0, null) } returns Unit
        val subject = FeishuAuthorizationPolicyServiceImpl(
            policies, retryMappings, identities, users, grants, mockk(relaxed = true), jacksonObjectMapper(), properties,
            organization, immediateTransactions()
        )

        val result = subject.syncAll()

        assertEquals("COMPLETED", result["status"])
        assertEquals(2, writes)
        verify(exactly = 1) { organization.facts("ou_p0") }
        verify(exactly = 1) { policies.finish(91, FeishuSyncStatus.COMPLETED, 1, 0, 0, 0, null) }
    }

    @Test
    fun `exhausted transient identity read marks the run failed and rethrows`() {
        val policies = mockk<FeishuPolicyRepository>()
        val identities = mockk<AuthExternalIdentityRepository>()
        val properties = enabledProperties(retryAttempts = 2)
        authenticate()
        every { policies.startExclusive(21, any()) } returns 92
        every { identities.allFeishu() } throws CannotAcquireLockException("connection unavailable")
        every { policies.finish(92, FeishuSyncStatus.FAILED, 0, 0, 0, 0, ErrorCode.INTERNAL_ERROR.name) } returns Unit
        val subject = FeishuAuthorizationPolicyServiceImpl(
            policies, mockk(), identities, mockk(), mockk(), mockk(), jacksonObjectMapper(), properties,
            mockk(), immediateTransactions()
        )

        assertThrows(CannotAcquireLockException::class.java) { subject.syncAll() }

        verify(exactly = 2) { identities.allFeishu() }
        verify(exactly = 1) {
            policies.finish(92, FeishuSyncStatus.FAILED, 0, 0, 0, 0, ErrorCode.INTERNAL_ERROR.name)
        }
    }

    @Test
    fun `active synchronization conflict is returned before loading identities`() {
        val policies = mockk<FeishuPolicyRepository>()
        val identities = mockk<AuthExternalIdentityRepository>()
        authenticate()
        every { policies.startExclusive(21, any()) } throws ServiceException(
            ErrorCode.FEISHU_SYNC_ALREADY_RUNNING,
            "FEISHU_POLICY_SYNC_ALREADY_RUNNING"
        )
        val subject = FeishuAuthorizationPolicyServiceImpl(
            policies, mockk(), identities, mockk(), mockk(), mockk(), jacksonObjectMapper(), enabledProperties(),
            mockk(), immediateTransactions()
        )

        val error = assertThrows(ServiceException::class.java) { subject.syncAll() }

        assertEquals(ErrorCode.FEISHU_SYNC_ALREADY_RUNNING, error.errorCode)
        verify(exactly = 0) { identities.allFeishu() }
    }

    private fun enabledProperties(retryAttempts: Int = 3) = FeishuSsoProperties(
        enabled = true,
        appId = "cli_p0_contract",
        appSecret = "test-secret",
        redirectUri = "https://feedback.example.test/auth/callback",
        publicBaseUrl = "https://feedback.example.test",
        organizationSyncEnabled = true,
        organizationSyncRetryAttempts = retryAttempts,
        organizationSyncRetryDelayMs = 0
    )

    private fun authenticate() {
        val principal = WebPrincipal(21, "admin", "", "Admin", setOf("ADMIN"), emptySet())
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, "")
    }

    private fun immediateTransactions(): TransactionOperations = object : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? =
            action.doInTransaction(mockk<TransactionStatus>())
    }
}
