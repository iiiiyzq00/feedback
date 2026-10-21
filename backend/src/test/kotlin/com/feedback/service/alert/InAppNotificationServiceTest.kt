package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import com.feedback.repository.alert.InAppAlertContext
import com.feedback.repository.alert.InAppNotificationRepository
import com.feedback.repository.alert.InAppReadRow
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class InAppNotificationServiceTest {
    private val repository = mockk<InAppNotificationRepository>()
    private val service = InAppNotificationService(repository, jacksonObjectMapper().findAndRegisterModules())
    private val time = LocalDateTime.of(2026, 9, 2, 10, 0)

    @Test
    fun `list preserves all five notification categories and target paths`() {
        every { repository.alertRows(7, 5, false) } returns listOf(
            InAppReadRow(
                InAppAlertNotificationPo(
                    id = 10,
                    alertInstanceId = 20,
                    ruleCode = "RULE_1",
                    content = """{"eventType":"ALERT_FIRED","title":"告警","summary":"告警摘要","severity":"P1"}""",
                    createdAt = time.plusMinutes(5),
                    deliveredAt = time.plusMinutes(5),
                ),
                null,
            ),
        )
        every { repository.alertContext(7, listOf(20)) } returns InAppAlertContext(
            incidentByAlert = mapOf(20L to InAppIncidentPo(30, "事件", "OPEN")),
            relatedCountByAlert = mapOf(20L to 2),
            alertById = mapOf(20L to InAppAlertPo(20, 40, time, "FIRING", "P1")),
            ruleById = mapOf(40L to InAppRulePo(40, "规则标题")),
            visibleSourceAlerts = setOf(20),
        )
        every { repository.handoffRows(7, 5, false) } returns listOf(InAppReadRow(InAppHandoffPo(1, 101, 8, "请接手", time.plusMinutes(4)), null))
        every { repository.reminderRows(7, 5, false) } returns listOf(InAppReadRow(InAppReminderPo(2, 102, 8, "ACCEPTANCE", "请接收", time.plusMinutes(3)), null))
        every { repository.dispositionRows(7, 5, false) } returns listOf(InAppReadRow(InAppDispositionPo(3, 103, 8, "REOPENED", "CLOSED", "OPEN", "复现", time.plusMinutes(2)), null))
        every { repository.productRows(7, 5, false) } returns listOf(InAppReadRow(InAppProductEventPo(4, 104, 8, "SUBMITTED_FOR_ACCEPTANCE", "请验收", 7, time.plusMinutes(1)), null))
        every { repository.userNames(listOf(8L, 8L, 8L, 8L)) } returns mapOf(8L to "张三")
        every { repository.requests(listOf(104L)) } returns mapOf(104L to InAppProductRequestPo(104, "REQ-104", "新增字段", 7))
        every { repository.unreadCount(7) } returns 5
        every { repository.total(7) } returns 5

        val page = service.list(7, 1, 5, false)

        assertEquals(5, page.records.size)
        assertEquals(
            listOf("ALERT_FIRED", "FEEDBACK_HANDOFF", "FEEDBACK_REMINDER", "FEEDBACK_STATUS_CHANGED", "PRODUCT_FEEDBACK_TASK"),
            page.records.map { it.eventType },
        )
        assertEquals("/alert/center?alertId=20", page.records.first().targetPath)
        assertEquals("/tasks/product-feedback?requestId=104", page.records.last().targetPath)
        assertEquals(2, page.records.first().relatedAlertCount)
        assertTrue(page.records.first().hasVisibleAlertNotification)
    }

    @Test
    fun `unread list uses unread total without changing the shared unread count`() {
        every { repository.alertRows(7, 20, true) } returns emptyList()
        every { repository.alertContext(7, emptyList()) } returns InAppAlertContext(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())
        every { repository.handoffRows(7, 20, true) } returns emptyList()
        every { repository.reminderRows(7, 20, true) } returns emptyList()
        every { repository.dispositionRows(7, 20, true) } returns emptyList()
        every { repository.productRows(7, 20, true) } returns emptyList()
        every { repository.userNames(emptyList()) } returns emptyMap()
        every { repository.requests(emptyList()) } returns emptyMap()
        every { repository.unreadCount(7) } returns 9

        val page = service.list(7, 1, 20, true)

        assertEquals(9, page.total)
        assertEquals(9, page.unreadCount)
        verify(exactly = 0) { repository.total(any()) }
    }

    @Test
    fun `read operations preserve visibility enforcement in repository`() {
        every { repository.markRead(7, -3_000_000_002L) } just runs
        every { repository.markAllRead(7) } returns 4

        assertTrue(service.markRead(7, -3_000_000_002L))
        assertEquals(4, service.markAllRead(7))

        verify { repository.markRead(7, -3_000_000_002L) }
        verify { repository.markAllRead(7) }
    }
}
