package com.feedback.service.alert

import com.feedback.model.po.alert.ClosureIncidentPo
import com.feedback.repository.alert.EntityClosureCounts
import com.feedback.repository.alert.EntityClosureFacts
import com.feedback.repository.alert.EntityClosureRepository
import com.feedback.repository.alert.EntityClosureSourceFact
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException

class EntityClosureServiceTest {
    private val repository=mockk<EntityClosureRepository>(relaxed=true)
    private val permissions=mockk<DataPermissionService>()
    private val service=EntityClosureService(repository,permissions)
    private val source=EntityClosureSourceFact(11,"JIRA","COURSE",null,null,"OPEN")

    private fun facts(effective:Long=1,processingStatus:String="OPEN")=EntityClosureFacts(
        ClosureIncidentPo("RESOLVED",2),listOf(source.copy(processingStatus=processingStatus)),
        EntityClosureCounts(1,1,1,1,1,1,effective),
    )
    private fun allow(){every{permissions.scope()} returns DataAccessScope(setOf("JIRA"),null,null)}

    @Test fun `reports ready only when every linked entity has closure evidence`() {
        allow();every{repository.facts(7)} returns facts()
        val result=service.snapshot(7)
        assertEquals("READY",result["status"]);assertEquals("entity-closure-v1",result["metricVersion"])
    }

    @Test fun `blocks closure without effective knowledge evidence`() {
        allow();every{repository.linkedCount(7)} returns 1;every{repository.facts(7)} returns facts(effective=0)
        assertThrows(com.feedback.common.exception.ClientException::class.java){service.closeLinkedFeedback(7,9,"证据不足")}
        verify(exactly=0){repository.closeSources(any(),any())}
    }

    @Test fun `atomically closes linked feedback after the readiness gate`() {
        allow();every{repository.linkedCount(7)} returns 1;every{repository.facts(7)} returns facts()
        service.closeLinkedFeedback(7,9,"闭环证据齐全")
        verify(exactly=1){repository.closeSources(7,9)};verify(exactly=1){repository.auditClose(7,9,"闭环证据齐全")}
    }

    @Test fun `fails closed when any linked source is outside data scope`() {
        every{permissions.scope()} returns DataAccessScope(setOf("SDK"),null,null);every{repository.facts(7)} returns facts()
        assertThrows(com.feedback.common.exception.ClientException::class.java){service.snapshot(7)}
    }
}
