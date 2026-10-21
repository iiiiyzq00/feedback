package com.feedback.service.jira

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.Jira400Properties
import com.feedback.repository.jira.JiraSyncState
import com.feedback.repository.jira.JiraSyncStateRepository
import com.feedback.service.imports.ImportBatchView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class Jira400SyncServiceTest {
    private val properties = Jira400Properties().apply {
        enabled = true
        baseUrl = "https://jira.example.test"
        allowedHosts = listOf("jira.example.test")
        token = "token"
        jql = "project = DEMO"
        pageSize = 1
        maxIssuesPerRun = 10
    }
    private val gateway = mockk<Jira400Gateway>()
    private val mapper = mockk<JiraIssueMapper>()
    private val states = mockk<JiraSyncStateRepository>(relaxed = true)
    private val importPort = mockk<JiraSyncImportPort>()
    private val clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneOffset.UTC)
    private val service = Jira400SyncService(properties, gateway, mapper, states, importPort, clock)
    private val mapping = mapOf(
        "source_record_id" to "key",
        "description" to "description",
        "source_update_time" to "updated"
    )
    private val contract = JiraFieldMappingContract(7, "v2.1.0", mapping)

    @Test
    fun `uses a thirty minute default interval between successful incremental runs`() {
        assertEquals(30, properties.scheduleIntervalMinutes)
    }

    @Test
    fun `fetches all pages and stages one API batch before advancing watermark`() {
        every { states.claim(any(), any()) } returns JiraSyncState(
            status = "RUNNING",
            watermark = LocalDateTime.parse("2026-08-14T02:00:00"),
            requestedTrigger = "SCHEDULED"
        )
        every { states.heartbeat(any(), any()) } returns true
        every { importPort.activeContract() } returns contract
        val fields = jacksonObjectMapper().createObjectNode()
        val first = JiraApiIssue("1", "DEMO-1", fields)
        val second = JiraApiIssue("2", "DEMO-2", fields)
        every { gateway.search(any(), 0, 1, listOf("description", "updated")) } returns JiraApiPage(listOf(first), 0, 2, false)
        every { gateway.search(any(), 1, 1, listOf("description", "updated")) } returns JiraApiPage(listOf(second), 1, 2, true)
        every { mapper.toMappedSourceRow(first, contract) } returns mapOf("key" to "DEMO-1")
        every { mapper.toMappedSourceRow(second, contract) } returns mapOf("key" to "DEMO-2")
        every { importPort.stage(any(), contract, "SCHEDULED", any(), any(), any(), null) } returns batch("batch-api", 2, "SCHEDULED")
        every { importPort.databaseBatchId("batch-api") } returns 91L

        service.runQueued()

        verify(exactly = 1) {
            gateway.search(
                match { it.contains("updated >= '2026-08-14 01:55'") && it.contains("updated <= '2026-08-14 12:00'") },
                0, 1, listOf("description", "updated")
            )
        }
        verify(exactly = 1) { importPort.stage(match { it.size == 2 }, contract, "SCHEDULED", any(), any(), any(), null) }
        verify(exactly = 1) { states.complete(properties.connectionKey, any(), LocalDateTime.parse("2026-08-14T12:00:00"), 91L, 30) }
    }

    @Test
    fun `does not enqueue while external contract is absent`() {
        properties.token = ""
        service.enqueueScheduled()
        verify(exactly = 0) { states.enqueueScheduledIfDue(any()) }
        assertEquals(false, properties.ready())
    }

    @Test
    fun `re-pulls formal review keys and marks missing ones`() {
        every { importPort.activeContract() } returns contract
        val issue = JiraApiIssue("1", "DEMO-1", jacksonObjectMapper().createObjectNode())
        every { gateway.search(any(), 0, 1, any()) } returns JiraApiPage(listOf(issue), 0, 1, true)
        every { mapper.toMappedSourceRow(issue, contract) } returns mapOf("key" to "DEMO-1")
        every { importPort.stage(any(), contract, "FORMAL_REVIEW", any(), any(), any(), 1L) } returns batch("batch-formal", 1, "FORMAL_REVIEW")
        every { importPort.markFormalReviewUnavailable(any()) } returns 1
        every { states.get(any()) } returns JiraSyncState(status = "IDLE")

        val result = service.rePullForFormalReview(listOf("DEMO-1", "DEMO-2"), 1L)

        assertEquals(listOf("DEMO-1"), result.fetchedKeys)
        assertEquals(listOf("DEMO-2"), result.missingKeys)
        assertEquals("batch-formal", result.batchId)
        verify(exactly = 0) { states.heartbeat(any(), any()) }
        verify(exactly = 1) { importPort.markFormalReviewUnavailable(listOf("DEMO-2")) }
    }

    private fun batch(id: String, rows: Int, trigger: String) = ImportBatchView(
        id, "JIRA", "PROCESSING", rows, 0, 0, 0, 0,
        LocalDateTime.now(), null, ingestMethod = "API", triggerType = trigger
    )
}
