package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.config.StationDualWriteProperties
import com.feedback.dto.station.StationAcceptResult
import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.security.station.StationGatewayPrincipal
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test

class StationDualWriteWorkerTest {
    private val tasks = mockk<StationDualWriteTaskService>()
    private val oldFbp = mockk<OldFbpClient>()
    private val ingest = mockk<StationFeedbackIngestService>()
    private val properties = StationDualWriteProperties().apply { enabled = true }
    private val worker = StationDualWriteWorker(tasks, oldFbp, ingest, properties)

    @Test
    fun `worker writes old FBP before formalizing new platform`() {
        val task = task(oldSucceeded = false)
        every { tasks.claimOne(any()) } returns task
        every { oldFbp.submit(task.envelope) } just runs
        every { tasks.markOldSucceeded(task.id, any()) } just runs
        every { ingest.accept(any(), any(), any(), any()) } returns StationAcceptResult("receipt", 42L)
        every { tasks.markCompleted(task.id, any(), 42L) } just runs

        worker.processOne()

        verify(exactly = 1) { oldFbp.submit(task.envelope) }
        verify(exactly = 1) { tasks.markOldSucceeded(task.id, any()) }
        verify(exactly = 1) { ingest.accept(any(), any(), any(), any()) }
        verify(exactly = 1) { tasks.markCompleted(task.id, any(), 42L) }
    }

    @Test
    fun `retry after old FBP success skips old HTTP`() {
        val task = task(oldSucceeded = true)
        every { tasks.claimOne(any()) } returns task
        every { ingest.accept(any(), any(), any(), any()) } returns StationAcceptResult("receipt", 43L)
        every { tasks.markCompleted(task.id, any(), 43L) } just runs

        worker.processOne()

        verify(exactly = 0) { oldFbp.submit(any()) }
        verify(exactly = 1) { ingest.accept(any(), any(), any(), any()) }
    }

    private fun task(oldSucceeded: Boolean): StationDualWriteTask {
        val request = StationFeedbackRequest(
            productId = "p1", title = "title", content = "content", phone = "13800138000", platform = "android"
        )
        val principal = StationGatewayPrincipal("rpc", "user-1", "study", adapter = true)
        return StationDualWriteTask(
            1L, StationDualWriteEnvelope(request, StationApiVersion.V1, "station:12345678", principal),
            oldSucceeded, false, 0, 8
        )
    }
}
