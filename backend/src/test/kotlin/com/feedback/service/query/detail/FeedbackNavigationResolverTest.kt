package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.repository.feedback.detail.FeedbackNavigationResolver
import com.feedback.common.exception.ServiceException
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackNavigationResolverTest {
    private val fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())
    private val reads = mockk<FeedbackNavigationReadPort>()
    private val resolver = FeedbackNavigationResolver(reads)
    private val scope = DataAccessScope(setOf("JIRA"), setOf("COURSE"), null)

    @Test
    fun `same timestamp candidates retain mapper order on both sides`() {
        val time = LocalDateTime.of(2026, 8, 29, 12, 0)
        val source = source(10, time)
        val previous = cursor(11, time)
        val next = cursor(9, time)
        every { reads.previousCandidates(any(), setOf("JIRA"), null, 200) } returns listOf(previous)
        every { reads.nextCandidates(any(), setOf("JIRA"), null, 200) } returns listOf(next)
        every { reads.content(listOf(11)) } returns mapOf(11L to content(11, "COURSE"))
        every { reads.content(listOf(9)) } returns mapOf(9L to content(9, "COURSE"))

        assertEquals(FeedbackNavigationSnapshot(11, 9), resolver.resolve(source, scope))
    }

    @Test
    fun `null timestamp cursor is passed through without synthetic time`() {
        every { reads.previousCandidates(match { it.id == 10L && it.faultOccurredAt == null }, any(), any(), 200) } returns emptyList()
        every { reads.nextCandidates(match { it.id == 10L && it.faultOccurredAt == null }, any(), any(), 200) } returns emptyList()

        assertEquals(FeedbackNavigationSnapshot(null, null), resolver.resolve(source(10, null), scope))
    }

    @Test
    fun `sparse scope advances by keyset batches with no per-row content lookup`() {
        val time = LocalDateTime.of(2026, 8, 29, 12, 0)
        val firstBatch = (1199L downTo 1000L).map { cursor(it, time.minusSeconds(1)) }
        val secondBatch = listOf(cursor(999, time.minusSeconds(2)))
        every { reads.previousCandidates(any(), any(), any(), 200) } returns emptyList()
        every { reads.nextCandidates(match { it.id == 1200L }, setOf("JIRA"), null, 200) } returns firstBatch
        every { reads.nextCandidates(match { it.id == 1000L }, setOf("JIRA"), null, 200) } returns secondBatch
        every { reads.content(firstBatch.map { it.id }) } returns firstBatch.associate { it.id to content(it.id, "OTHER") }
        every { reads.content(listOf(999)) } returns mapOf(999L to content(999, "COURSE"))

        assertEquals(999, resolver.resolve(source(1200, time), scope).nextId)
        verify(exactly = 2) { reads.nextCandidates(any(), any(), any(), 200) }
        verify(exactly = 2) { reads.content(any()) }
    }

    @Test
    fun `candidate order and all visibility facts are preserved within one bulk content read`() {
        val restrictedScope = DataAccessScope(setOf("JIRA"), setOf("COURSE"), setOf("CN-11"))
        val candidates = listOf(
            cursor(15, null, province = "CN-11"),
            cursor(14, null, province = "CN-11"),
            cursor(13, null, province = "CN-11"),
            cursor(12, null, province = "CN-12"),
            cursor(11, null, province = "CN-11")
        )
        every { reads.previousCandidates(any(), setOf("JIRA"), setOf("CN-11"), 200) } returns candidates
        every { reads.nextCandidates(any(), setOf("JIRA"), setOf("CN-11"), 200) } returns emptyList()
        every { reads.content(candidates.map { it.id }) } returns mapOf(
            15L to content(15, "COURSE", deleted = true),
            14L to content(14, "COURSE", maskingStatus = "PENDING"),
            13L to content(13, "OTHER"),
            12L to content(12, "COURSE"),
            11L to content(11, "COURSE")
        )

        assertEquals(FeedbackNavigationSnapshot(11, null), resolver.resolve(source(10, null), restrictedScope))
        verify(exactly = 1) { reads.content(candidates.map { it.id }) }
    }

    @Test
    fun `short invisible batch proves boundary without consuming another batch`() {
        val candidates = listOf(cursor(11, null), cursor(12, null))
        every { reads.previousCandidates(any(), any(), any(), 200) } returns candidates
        every { reads.nextCandidates(any(), any(), any(), 200) } returns emptyList()
        every { reads.content(candidates.map { it.id }) } returns
            candidates.associate { it.id to content(it.id, "OTHER") }

        assertEquals(FeedbackNavigationSnapshot(null, null), resolver.resolve(source(10, null), scope))
        verify(exactly = 1) { reads.previousCandidates(any(), any(), any(), 200) }
        verify(exactly = 1) { reads.nextCandidates(any(), any(), any(), 200) }
        verify(exactly = 1) { reads.content(any()) }
    }

    @Test
    fun `empty candidate results never trigger content reads`() {
        every { reads.previousCandidates(any(), any(), any(), 200) } returns emptyList()
        every { reads.nextCandidates(any(), any(), any(), 200) } returns emptyList()

        assertEquals(FeedbackNavigationSnapshot(null, null), resolver.resolve(source(10, null), scope))
        verify(exactly = 1) { reads.previousCandidates(any(), any(), any(), 200) }
        verify(exactly = 1) { reads.nextCandidates(any(), any(), any(), 200) }
        verify(exactly = 0) { reads.content(any()) }
    }

    @Test
    fun `previous direction fails closed after exactly five full invisible batches`() {
        val batches = fullBatches(start = 1_000, ascending = true)
        every { reads.previousCandidates(any(), any(), any(), 200) } returnsMany batches
        every { reads.content(any()) } answers {
            firstArg<List<Long>>().associateWith { content(it, "OTHER") }
        }

        val error = assertThrows(ServiceException::class.java) {
            resolver.resolve(source(10, null), scope)
        }

        assertEquals("FEEDBACK_NAVIGATION_SCAN_BUDGET_EXHAUSTED", error.reasonCode)
        assertEquals(1_000, error.details["candidateBudgetPerDirection"])
        verify(exactly = 5) { reads.previousCandidates(any(), any(), any(), 200) }
        verify(exactly = 5) { reads.content(match { it.size == 200 }) }
        verify(exactly = 0) { reads.nextCandidates(any(), any(), any(), any()) }
    }

    @Test
    fun `next direction fails closed after exactly five full invisible batches`() {
        val batches = fullBatches(start = 10_000, ascending = false)
        every { reads.previousCandidates(any(), any(), any(), 200) } returns emptyList()
        every { reads.nextCandidates(any(), any(), any(), 200) } returnsMany batches
        every { reads.content(any()) } answers {
            firstArg<List<Long>>().associateWith { content(it, "OTHER") }
        }

        val error = assertThrows(ServiceException::class.java) {
            resolver.resolve(source(20_000, null), scope)
        }

        assertEquals("FEEDBACK_NAVIGATION_SCAN_BUDGET_EXHAUSTED", error.reasonCode)
        assertEquals(1_000, error.details["candidateBudgetPerDirection"])
        verify(exactly = 1) { reads.previousCandidates(any(), any(), any(), 200) }
        verify(exactly = 5) { reads.nextCandidates(any(), any(), any(), 200) }
        verify(exactly = 5) { reads.content(match { it.size == 200 }) }
    }

    private fun source(id: Long, time: LocalDateTime?) = FeedbackSourceSnapshot(
        id, "JIRA", "FP-$id", null, null, null, null, time,
        null, null, null, null, null, null
    )
    private fun cursor(
        id: Long,
        time: LocalDateTime?,
        province: String? = null,
        city: String? = null
    ) = FeedbackNavigationCursorSnapshot(id, "JIRA", province, city, time)

    private fun content(
        id: Long,
        module: String,
        maskingStatus: String = "PASS",
        deleted: Boolean = false
    ) = FeedbackNavigationContentSnapshot(id, module, maskingStatus, deleted)

    private fun fullBatches(start: Long, ascending: Boolean): List<List<FeedbackNavigationCursorSnapshot>> =
        (0 until 5).map { batch ->
            (0 until 200).map { offset ->
                val delta = (batch * 200 + offset).toLong()
                cursor(if (ascending) start + delta else start - delta, null)
            }
        }
}
