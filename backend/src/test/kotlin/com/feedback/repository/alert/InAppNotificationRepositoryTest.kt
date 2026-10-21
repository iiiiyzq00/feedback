package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class InAppNotificationRepositoryTest {
    private val alerts = mockk<InAppAlertNotificationMapper>()
    private val handoffs = mockk<InAppHandoffMapper>()
    private val reminders = mockk<InAppReminderMapper>()
    private val dispositions = mockk<InAppDispositionMapper>()
    private val assignments = mockk<InAppAssignmentMapper>()
    private val productEvents = mockk<InAppProductEventMapper>()
    private val productRequests = mockk<InAppProductRequestMapper>()
    private val users = mockk<InAppUserMapper>()
    private val receipts = mockk<InAppReceiptMapper>()
    private val incidentLinks = mockk<InAppIncidentLinkMapper>()
    private val incidents = mockk<InAppIncidentMapper>()
    private val alertInstances = mockk<InAppAlertMapper>()
    private val rules = mockk<InAppRuleMapper>()
    private val repository = InAppNotificationRepository(
        alerts, handoffs, reminders, dispositions, assignments, productEvents,
        productRequests, users, receipts, incidentLinks, incidents, alertInstances, rules,
    )

    @Test
    fun `unread page continues after a complete batch of read rows`() {
        val first = (1000L downTo 501L).map { InAppAlertNotificationPo(id = it, createdAt = LocalDateTime.MIN) }
        every { alerts.page(7, null, 500) } returns first
        every { alerts.page(7, 501, 500) } returns listOf(InAppAlertNotificationPo(id = 500, createdAt = LocalDateTime.MIN))
        every { receipts.byTargets(7, "ALERT_NOTIFICATION", first.map { it.id.toString() }) } returns
            first.map { InAppReceiptPo(it.id.toString(), LocalDateTime.MIN) }
        every { receipts.byTargets(7, "ALERT_NOTIFICATION", listOf("500")) } returns emptyList()

        val rows = repository.alertRows(7, 1, true)

        assertEquals(listOf(500L), rows.map { it.value.id })
        verify(exactly = 2) { alerts.page(7, any(), 500) }
    }

    @Test
    fun `mark all read batches each visible source without joins or per-row writes`() {
        val time = LocalDateTime.of(2026, 9, 2, 10, 0)
        every { alerts.page(7, null, 500) } returns listOf(InAppAlertNotificationPo(id = 1, createdAt = time))
        every { handoffs.page(7, null, 500) } returns listOf(InAppHandoffPo(id = 2, feedbackId = 20, createdAt = time))
        every { reminders.page(7, null, 500) } returns listOf(InAppReminderPo(id = 3, feedbackId = 30, createdAt = time))
        every { dispositions.page(7, null, 500) } returns listOf(InAppDispositionPo(id = 4, feedbackId = 40, actorId = 8, eventType = "STATUS_CHANGED", createdAt = time))
        every { assignments.owners(listOf(40)) } returns listOf(InAppAssignmentPo(40, 7))
        every { productEvents.page(7, null, 500) } returns listOf(InAppProductEventPo(id = 5, requestId = 50, actorId = 8, eventType = "ASSIGNED", newHandlerUserId = 7, createdAt = time))
        every { productRequests.byIds(listOf(50)) } returns listOf(InAppProductRequestPo(50, "REQ-50", "标题", 9))
        every { receipts.insertBatch(any()) } answers { firstArg<List<InAppReceiptInsertPo>>().size }

        val marked = repository.markAllRead(7)

        assertEquals(5, marked)
        verify(exactly = 5) { receipts.insertBatch(match { it.size == 1 }) }
    }
}
