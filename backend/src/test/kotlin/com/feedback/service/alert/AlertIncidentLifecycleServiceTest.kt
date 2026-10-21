package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.AlertLifecycleStatePo
import com.feedback.model.po.alert.IncidentLifecycleStatePo
import com.feedback.repository.alert.AlertIncidentLifecycleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AlertIncidentLifecycleServiceTest {
    private val repository = mockk<AlertIncidentLifecycleRepository>(relaxed = true)
    private val closure = mockk<EntityClosureService>(relaxed = true)
    private val service = AlertIncidentLifecycleService(repository, ObjectMapper(), closure)

    @Test
    fun `preserves every allowed alert transition and appends the next version`() {
        val transitions = mapOf(
            "PENDING" to listOf("FIRING", "CLOSED"),
            "FIRING" to listOf("ACKNOWLEDGED", "SUPPRESSED", "ESCALATED", "RECOVERED"),
            "ESCALATED" to listOf("ACKNOWLEDGED", "SUPPRESSED", "RECOVERED"),
            "ACKNOWLEDGED" to listOf("SUPPRESSED", "RECOVERED", "RESOLVED", "CLOSED"),
            "SUPPRESSED" to listOf("FIRING", "ACKNOWLEDGED", "RECOVERED", "RESOLVED"),
            "RECOVERED" to listOf("FIRING", "CLOSED"),
            "RESOLVED" to listOf("CLOSED", "ARCHIVED"),
            "CLOSED" to listOf("ARCHIVED"),
        )
        every { repository.transitionAlert(any(), any(), any(), any(), any()) } returns true

        var id = 1L
        transitions.forEach { (from, targets) ->
            targets.forEach { target ->
                every { repository.lockAlert(id) } returns AlertLifecycleStatePo(from, 7, "P1")
                service.transitionAlert(id, target, 7, "状态迁移", 11)
                verify(exactly = 1) { repository.appendEvent("ALERT", id, from, target, 8, "状态迁移", "{}", 11) }
                id++
            }
        }
        assertEquals(23L, id)
    }

    @Test
    fun `rejects invalid alert transition and optimistic version conflict before update`() {
        every { repository.lockAlert(1) } returns AlertLifecycleStatePo("PENDING", 3, "P1")
        assertEquals("ALERT_TRANSITION_INVALID", assertThrows(com.feedback.common.exception.ClientException::class.java) {
            service.transitionAlert(1, "RESOLVED", 3, "非法迁移", 9)
        }.reasonCode)
        assertEquals("ALERT_VERSION_CONFLICT", assertThrows(com.feedback.common.exception.ClientException::class.java) {
            service.transitionAlert(1, "FIRING", 2, "版本冲突", 9)
        }.reasonCode)
        verify(exactly = 0) { repository.transitionAlert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P0 resolution requires both a human actor and a real conclusion`() {
        every { repository.lockAlert(8) } returns AlertLifecycleStatePo("ACKNOWLEDGED", 4, "P0")
        listOf(null, "", " ", "a").forEach { conclusion ->
            val evidence = if (conclusion == null) emptyMap() else mapOf("conclusion" to conclusion)
            assertEquals("P0_MANUAL_CONCLUSION_REQUIRED", assertThrows(com.feedback.common.exception.ClientException::class.java) {
                service.transitionAlert(8, "RESOLVED", 4, "解决告警", 12, evidence)
            }.reasonCode)
        }
        assertEquals("P0_MANUAL_CONCLUSION_REQUIRED", assertThrows(com.feedback.common.exception.ClientException::class.java) {
            service.transitionAlert(8, "RESOLVED", 4, "解决告警", null, mapOf("conclusion" to "问题已恢复"))
        }.reasonCode)
        every { repository.transitionAlert(8, "RESOLVED", 4, "解决告警", 12) } returns true
        service.transitionAlert(8, "RESOLVED", 4, "解决告警", 12, mapOf("conclusion" to "问题已恢复"))
    }

    @Test
    fun `preserves every allowed incident transition and evidence gates`() {
        val transitions = listOf(
            Triple("OPEN", "INVESTIGATING", mapOf<String, Any?>("commanderId" to 31L)),
            Triple("INVESTIGATING", "RESOLVED", mapOf<String, Any?>("resolution" to "根因已修复")),
            Triple("RESOLVED", "INVESTIGATING", mapOf<String, Any?>("commanderId" to 31L)),
            Triple("RESOLVED", "CLOSED", mapOf<String, Any?>("closure" to "闭环证据完整")),
            Triple("CLOSED", "ARCHIVED", emptyMap()),
        )
        every { repository.transitionIncident(any(), any(), any(), any(), any(), any(), any()) } returns true
        transitions.forEachIndexed { index, (from, target, evidence) ->
            val id = index + 1L
            every { repository.lockIncident(id) } returns IncidentLifecycleStatePo(from, 5, null)
            service.transitionIncident(id, target, 5, "事故迁移", 9, evidence)
            verify(exactly = 1) { repository.appendEvent("INCIDENT", id, from, target, 6, "事故迁移", any(), 9) }
        }
        verify(exactly = 1) { closure.closeLinkedFeedback(4, 9, "事故迁移") }
    }

    @Test
    fun `incident close invokes linked feedback closure before persistence`() {
        every { repository.lockIncident(7) } returns IncidentLifecycleStatePo("RESOLVED", 2, 9)
        every { repository.transitionIncident(any(), any(), any(), any(), any(), any(), any()) } returns true
        val order = mutableListOf<String>()
        every { closure.closeLinkedFeedback(7, 9, "完成闭环") } answers { order += "closure" }
        every { repository.transitionIncident(7, "CLOSED", 2, "完成闭环", 9, "证据齐全", null) } answers { order += "transition"; true }

        service.transitionIncident(7, "CLOSED", 2, "完成闭环", 9, mapOf("closure" to "证据齐全"))

        assertEquals(listOf("closure", "transition"), order)
    }
}
