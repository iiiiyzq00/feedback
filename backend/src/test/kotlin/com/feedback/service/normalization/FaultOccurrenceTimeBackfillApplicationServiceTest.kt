package com.feedback.service.normalization

import com.feedback.model.po.normalization.FaultOccurrenceTimeContentPo
import com.feedback.model.po.normalization.FaultOccurrenceTimeJiraPo
import com.feedback.model.po.normalization.FaultOccurrenceTimeQualityPo
import com.feedback.model.po.normalization.FaultOccurrenceTimeSourcePo
import com.feedback.model.po.normalization.FaultOccurrenceTimeStationPo
import com.feedback.repository.normalization.FaultOccurrenceTimeRepository
import com.feedback.repository.normalization.FaultOccurrenceTimeUpdate
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

class FaultOccurrenceTimeBackfillApplicationServiceTest {
    private val repository = mockk<FaultOccurrenceTimeRepository>()
    private val dirtyMarker = mockk<FeedbackListProjectionDirtyMarker>()
    private val service = FaultOccurrenceTimeBackfillApplicationService(repository, dirtyMarker)

    @Test
    fun `batch reads each source table once and marks only updated sources`() {
        val reference = LocalDateTime.of(2026, 8, 30, 9, 0)
        val sources = listOf(
            FaultOccurrenceTimeSourcePo(1, "SDK", reference),
            FaultOccurrenceTimeSourcePo(2, "JIRA", reference),
            FaultOccurrenceTimeSourcePo(3, "STATION_FEEDBACK", reference),
            FaultOccurrenceTimeSourcePo(4, "QUALITY", reference),
            FaultOccurrenceTimeSourcePo(5, "REDBLACK", reference)
        )
        every { repository.findPendingAfter(0, 200) } returns sources
        every { repository.contents(listOf(1, 2, 3, 4, 5)) } returns mapOf(
            1L to FaultOccurrenceTimeContentPo(1, "SDK content"),
            2L to FaultOccurrenceTimeContentPo(2, "【出现故障时间（精确到分）】：2026-08-30 08:00"),
            3L to FaultOccurrenceTimeContentPo(3, "没有时间"),
            4L to FaultOccurrenceTimeContentPo(4, "没有时间"),
            5L to FaultOccurrenceTimeContentPo(5, "Redblack content")
        )
        every { repository.jira(listOf(2)) } returns mapOf(2L to FaultOccurrenceTimeJiraPo(2, reference))
        every { repository.station(listOf(3)) } returns mapOf(
            3L to FaultOccurrenceTimeStationPo(3, "故障时间：2026-08-30 07:00")
        )
        every { repository.quality(listOf(4)) } returns mapOf(
            4L to FaultOccurrenceTimeQualityPo(4, "故障时间：2026-08-30 06:00", "补充说明")
        )
        val updates = slot<List<FaultOccurrenceTimeUpdate>>()
        every { repository.updateAll(capture(updates)) } returns Unit
        every { dirtyMarker.mark(any()) } returns Unit

        val result = service.processBatch(0)

        assertEquals(5, result.updatedCount)
        assertEquals(5, result.nextSourceId)
        assertEquals(
            listOf("RESOLVED", "RESOLVED", "RESOLVED", "RESOLVED", "NOT_APPLICABLE"),
            updates.captured.map { it.resolution }
        )
        assertEquals(reference, updates.captured[0].occurredAt)
        assertEquals(LocalDateTime.of(2026, 8, 30, 8, 0), updates.captured[1].occurredAt)
        assertEquals(LocalDateTime.of(2026, 8, 30, 7, 0), updates.captured[2].occurredAt)
        assertEquals(LocalDateTime.of(2026, 8, 30, 6, 0), updates.captured[3].occurredAt)
        verifyOrder {
            repository.findPendingAfter(0, 200)
            repository.contents(listOf(1, 2, 3, 4, 5))
            repository.jira(listOf(2))
            repository.station(listOf(3))
            repository.quality(listOf(4))
            repository.updateAll(any())
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(1, 2, 3, 4, 5), FeedbackListProjectionDirtyReason.SOURCE))
        }
    }

    @Test
    fun `empty batch performs no auxiliary reads or dirty mark`() {
        every { repository.findPendingAfter(9, 200) } returns emptyList()

        assertEquals(FaultOccurrenceTimeBatchResult.empty(9), service.processBatch(9))

        verify(exactly = 0) { repository.contents(any()) }
        verify(exactly = 0) { repository.updateAll(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    @Test
    fun `source without content stays pending and is not marked dirty`() {
        every { repository.findPendingAfter(0, 200) } returns listOf(FaultOccurrenceTimeSourcePo(8, "SDK", LocalDateTime.now()))
        every { repository.contents(listOf(8)) } returns emptyMap()
        every { repository.jira(emptyList()) } returns emptyMap()
        every { repository.station(emptyList()) } returns emptyMap()
        every { repository.quality(emptyList()) } returns emptyMap()

        val result = service.processBatch(0)

        assertEquals(1, result.candidateCount)
        assertEquals(0, result.updatedCount)
        assertEquals(8, result.nextSourceId)
        verify(exactly = 0) { repository.updateAll(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    @Test
    fun `marker failure propagates after source update for transaction rollback`() {
        every { repository.findPendingAfter(0, 200) } returns listOf(FaultOccurrenceTimeSourcePo(7, "SDK", LocalDateTime.now()))
        every { repository.contents(listOf(7)) } returns mapOf(7L to FaultOccurrenceTimeContentPo(7, "SDK content"))
        every { repository.jira(emptyList()) } returns emptyMap()
        every { repository.station(emptyList()) } returns emptyMap()
        every { repository.quality(emptyList()) } returns emptyMap()
        every { repository.updateAll(any()) } returns Unit
        every { dirtyMarker.mark(any()) } throws IllegalStateException("DIRTY_MARK_FAILED")

        assertThrows(IllegalStateException::class.java) { service.processBatch(0) }
        verify(exactly = 1) { repository.updateAll(any()) }
    }

    @Test
    fun `batch method owns an independent authority transaction`() {
        val annotation = FaultOccurrenceTimeBackfillApplicationService::class.java
            .getDeclaredMethod("processBatch", Long::class.javaPrimitiveType)
            .getAnnotation(Transactional::class.java)

        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation)
    }
}
