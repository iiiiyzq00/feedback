package com.feedback.security

import com.feedback.model.auth.*
import io.mockk.mockk
import com.feedback.model.auth.DataAccessLevel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataPermissionSubjectType
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.RoleCode
import com.feedback.repository.auth.AuthDataPermission
import com.feedback.repository.auth.AuthDataPermissionRepository
import com.feedback.service.impl.auth.DataPermissionServiceImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

class DataPermissionServiceTest {
    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test
    fun `missing authentication context cannot read any channel`() {
        SecurityContextHolder.clearContext()
        val service = DataPermissionServiceImpl(mockk())
        assertTrue(service.allowedChannels().isEmpty())
    }

    @Test fun `user module and region allow lists are combined with channel scope`() {
        val repository=mockk<AuthDataPermissionRepository>()
        val principal=WebPrincipal(5,"auditor","hash","A",setOf("AUDITOR"),emptySet())
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)
        every{repository.findReadPermissions(5,setOf(RoleCode.AUDITOR))} returns listOf(
            row("CHANNEL","SDK","ALLOW"), row("MODULE","AI_TUTOR","ALLOW"),
            row("REGION","SHANGHAI","ALLOW"))
        val scope=DataPermissionServiceImpl(repository).scope()
        assertEquals(setOf("SDK"),scope.channels);assertEquals(setOf("AI_TUTOR"),scope.modules);assertEquals(setOf("SHANGHAI"),scope.regions)
        assertTrue(scope.permits("SDK","AI_TUTOR","SHANGHAI",null))
        assertTrue(!scope.permits("JIRA","AI_TUTOR","SHANGHAI",null))
    }

    @Test fun `admin bypasses data scopes for every channel module and region`() {
        authenticate(WebPrincipal(1,"admin","hash","Admin",setOf("ADMIN"),emptySet()))
        val scope=DataPermissionServiceImpl(mockk()).scope()
        assertEquals(setOf("SDK","STATION_FEEDBACK","JIRA","QUALITY","REDBLACK"),scope.channels)
        assertTrue(scope.permits("STATION_FEEDBACK","ANY_MODULE","ANY_PROVINCE","ANY_CITY"))
        assertTrue(scope.permits("REDBLACK","ANY_MODULE","ANY_PROVINCE","ANY_CITY"))
    }

    @Test fun `operator wildcard channel permits all channels without module or region restriction`() {
        val scope=scopeFor("OPERATOR", listOf(row("CHANNEL","*","ALLOW")))
        assertTrue(scope.permits("SDK",null,null,null))
        assertTrue(scope.permits("STATION_FEEDBACK",null,null,null))
        assertTrue(scope.permits("JIRA","AI_TUTOR","BEIJING",null))
        assertTrue(scope.permits("QUALITY","OTHER",null,"SHANGHAI"))
        assertTrue(scope.permits("REDBLACK",null,null,null))
    }

    @Test fun `auditor allow lists enforce channel module and either province or city`() {
        val scope=scopeFor("AUDITOR", listOf(
            row("CHANNEL","SDK","ALLOW"), row("CHANNEL","QUALITY","ALLOW"),
            row("MODULE","AI_TUTOR","ALLOW"), row("REGION","SHANGHAI","ALLOW")
        ))
        assertTrue(scope.permits("SDK","AI_TUTOR","SHANGHAI",null))
        assertTrue(scope.permits("QUALITY","AI_TUTOR","OTHER","SHANGHAI"))
        assertFalse(scope.permits("JIRA","AI_TUTOR","SHANGHAI",null))
        assertFalse(scope.permits("SDK","WRONG_BOOK","SHANGHAI",null))
        assertFalse(scope.permits("SDK","AI_TUTOR","BEIJING",null))
    }

    @Test fun `ordinary user default scope permits SDK only`() {
        val scope=scopeFor("USER", listOf(row("CHANNEL","SDK","ALLOW")))
        assertTrue(scope.permits("SDK",null,null,null))
        assertFalse(scope.permits("JIRA",null,null,null))
        assertFalse(scope.permits("QUALITY",null,null,null))
        assertFalse(scope.permits("REDBLACK",null,null,null))
    }

    @Test fun `deny overrides allows from either user or role`() {
        val scope=scopeFor("AUDITOR", listOf(
            row("CHANNEL","*","ALLOW"), row("CHANNEL","JIRA","DENY"),
            row("MODULE","AI_TUTOR","ALLOW"), row("MODULE","AI_TUTOR","DENY")
        ))
        assertTrue(scope.permits("SDK","OTHER",null,null).not()) // 显式模块范围为空
        assertFalse(scope.permits("JIRA","AI_TUTOR",null,null))
        assertFalse(scope.permits("SDK","AI_TUTOR",null,null))
    }

    @Test fun `wildcard deny revokes an entire data dimension`() {
        val repository=mockk<AuthDataPermissionRepository>()
        authenticate(WebPrincipal(9,"user","hash","User",setOf("USER"),emptySet()))
        every{repository.findReadPermissions(9,setOf(RoleCode.USER))} returns listOf(
            row("CHANNEL","*","ALLOW"), row("CHANNEL","*","DENY")
        )
        assertTrue(DataPermissionServiceImpl(repository).allowedChannels().isEmpty())
    }

    @Test fun `combined snapshot reads authorization facts once`() {
        val repository = mockk<AuthDataPermissionRepository>()
        authenticate(WebPrincipal(9, "user", "hash", "User", setOf("USER"), emptySet()))
        every { repository.findReadPermissions(9, setOf(RoleCode.USER)) } returns listOf(
            row("CHANNEL", "SDK", "ALLOW"),
            row("FIELD", "TITLE", "ALLOW"),
        )

        val snapshot = DataPermissionServiceImpl(repository).snapshot()

        assertEquals(setOf("SDK"), snapshot.scope.channels)
        assertTrue(snapshot.fieldPolicy.permits("title"))
        verify(exactly = 1) { repository.findReadPermissions(9, setOf(RoleCode.USER)) }
    }

    private fun scopeFor(role:String,rows:List<AuthDataPermission>):DataAccessScope {
        val repository=mockk<AuthDataPermissionRepository>()
        authenticate(WebPrincipal(8,role.lowercase(),"hash",role,setOf(role),emptySet()))
        every{repository.findReadPermissions(8,setOf(RoleCode.requireStorage(role)))} returns rows
        return DataPermissionServiceImpl(repository).scope()
    }
    private fun authenticate(principal:WebPrincipal){SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)}
    private fun row(type:String,value:String,effect:String)=AuthDataPermission(
        id=1,subjectType=DataPermissionSubjectType.ROLE,subjectId="TEST",
        scopeType=DataScopeType.requireStorage(type),scopeValue=value,accessLevel=DataAccessLevel.READ,
        effect=DataPermissionEffect.requireStorage(effect)
    )
}
