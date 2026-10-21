package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlertRuleIsolatedRepositoryTest {
    private val rules=mockk<IsolatedRuleMapper>()
    private val tasks=mockk<IsolatedTaskMapper>()
    private val users=mockk<IsolatedUserMapper>()
    private val audits=mockk<IsolatedAuditMapper>()
    private val repository=AlertRuleIsolatedRepository(rules,tasks,users,audits)

    @Test fun `drill list preserves parent and user inner join filtering`() {
        every { tasks.drillPage(null,500) } returns listOf(IsolatedDrillPo(3,30,10,1,userId=8),IsolatedDrillPo(2,20,11,1,userId=9))
        every { tasks.parents(listOf(10,11)) } returns listOf(IsolatedParentPo(10,100))
        every { users.users(listOf(8,9)) } returns listOf(IsolatedUserPo(8,"甲"),IsolatedUserPo(9,"乙"))
        val result=repository.drills(20)
        assertEquals(1,result.size);assertEquals(30,result.single().id);assertEquals(100,result.single().testRunId)
    }

    @Test fun `transition audit targets external display id`() {
        val row=IsolatedLockedDrillPo(88,"OPEN",false,"IN_APP");every { tasks.transition(88,"ACKNOWLEDGED","已确认",7) } returns 1
        val audit=slot<IsolatedAuditInsertPo>();every { audits.insert(capture(audit)) } returns 1
        repository.transition(row,"ACKNOWLEDGED","已确认",7,9001)
        assertEquals("9001",audit.captured.targetId);assertEquals("OPEN",audit.captured.fromStatus)
    }
}
