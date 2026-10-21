package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.query.alert.AlertQueryCriteria
import com.feedback.model.po.alert.AlertQueryInstancePo
import com.feedback.model.po.alert.AlertQueryRulePo
import com.feedback.repository.alert.AlertQueryRepository
import com.feedback.repository.alert.AlertVisibilityFact
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime

class AlertQueryServiceTest {
    private val repository=mockk<AlertQueryRepository>(relaxed=true)
    private val permissions=mockk<DataPermissionService>()
    private val service=AlertQueryService(repository,ObjectMapper(),permissions,mockk(relaxed=true),mockk(relaxed=true))

    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `list filters data scope before total and page are calculated`() {
        principal(setOf("PHASE_ONE_PRODUCT"));every{permissions.scope()} returns DataAccessScope(setOf("SDK"),null,null)
        val rows=listOf(
            AlertQueryInstancePo(1,11,101,null,"P1","FIRING",0,null,LocalDateTime.now()),
            AlertQueryInstancePo(2,12,102,null,"P1","FIRING",0,null,LocalDateTime.now()),
            AlertQueryInstancePo(3,13,null,null,"P1","FIRING",0,null,LocalDateTime.now()),
        )
        every{repository.page(AlertQueryCriteria(null,null,false,null,null),0,500)} returns rows
        val rules=rows.associate{it.ruleId to AlertQueryRulePo(it.ruleId,"R${it.ruleId}","规则${it.ruleId}")}
        every{repository.ruleMap(any())} answers { val input=arg<Collection<AlertQueryInstancePo>>(0);rules.filterKeys{key->input.any{it.ruleId==key}} }
        every{repository.visibility(rows)} returns mapOf(1L to listOf(AlertVisibilityFact("SDK",null,null,null)),2L to listOf(AlertVisibilityFact("JIRA",null,null,null)),3L to emptyList())
        val result=service.list(null,null,1,20)
        assertEquals(1L,result["total"]);assertEquals(1L,((result["records"] as List<*>).single() as Map<*,*>)["id"])
    }

    @Test fun `admin still excludes alert whose rule row is missing`() {
        principal(setOf("ADMIN"));val rows=listOf(AlertQueryInstancePo(1,11),AlertQueryInstancePo(2,12))
        every{repository.page(any(),0,500)} returns rows
        every{repository.ruleMap(rows)} returns mapOf(11L to AlertQueryRulePo(11,"R11","规则11"))
        every{repository.ruleMap(listOf(rows.first()))} returns mapOf(11L to AlertQueryRulePo(11,"R11","规则11"))
        val result=service.list(null,null,1,20)
        assertEquals(1L,result["total"])
    }

    private fun principal(roles:Set<String>){val p=WebPrincipal(9,"user","","User",roles,emptySet());SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,"")}
}
