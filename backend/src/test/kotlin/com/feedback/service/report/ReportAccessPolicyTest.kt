package com.feedback.service.report

import com.feedback.common.exception.ClientException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class ReportAccessPolicyTest {
    private val permissions=mockk<DataPermissionService>()
    private val policy=ReportAccessPolicy(permissions,jacksonObjectMapper())
    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `operator can read scheduled report when report scope is contained`() {
        authenticate("OPERATOR",setOf("access:ai_reports","action:generate_reports"))
        every{permissions.scope()} returns DataAccessScope(setOf("SDK","JIRA"),setOf("AUDIO"),null)
        assertTrue(policy.canView("DAILY_INGEST","""{"channels":["SDK"],"modules":["AUDIO"],"regions":null}"""))
    }

    @Test fun `restricted user cannot read a wider report snapshot`() {
        authenticate("OPERATOR",setOf("access:ai_reports"))
        every{permissions.scope()} returns DataAccessScope(setOf("SDK"),setOf("AUDIO"),setOf("SHANGHAI"))
        assertFalse(policy.canView("DAILY_INGEST","""{"channels":["SDK","JIRA"],"modules":["AUDIO"],"regions":["SHANGHAI"]}"""))
        assertFalse(policy.canView("DAILY_INGEST","""{"channels":["SDK"],"modules":null,"regions":["SHANGHAI"]}"""))
        assertFalse(policy.canView("DAILY_INGEST",null))
    }

    @Test fun `auditor is read only and limited to linkage report types`() {
        authenticate("AUDITOR",setOf("access:ai_reports"))
        every{permissions.scope()} returns DataAccessScope(setOf("SDK","QUALITY","REDBLACK"),null,null)
        val scope="""{"channels":["SDK","QUALITY"],"modules":null,"regions":null}"""
        assertTrue(policy.canView("QUALITY_LINKAGE",scope))
        assertFalse(policy.canView("DAILY_INGEST",scope))
        assertThrows(ClientException::class.java){policy.requireGenerate("QUALITY_LINKAGE")}
    }

    @Test fun `administrator bypasses report scope restriction`() {
        authenticate("ADMIN",emptySet())
        assertTrue(policy.canView("RISK_BRIEF","""{"channels":["SDK","JIRA","QUALITY","REDBLACK"],"modules":null,"regions":null}"""))
        policy.requireGenerate("RISK_BRIEF")
    }

    private fun authenticate(role:String,permissionSet:Set<String>){val principal=WebPrincipal(7,role.lowercase(),"","",setOf(role),permissionSet);SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.authorities)}
}
