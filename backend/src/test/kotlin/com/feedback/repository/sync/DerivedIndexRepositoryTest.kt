package com.feedback.repository.sync

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.mapper.sync.*
import com.feedback.model.po.sync.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DerivedIndexRepositoryTest {
    @Test
    fun `seed scans past existing first page before applying five hundred missing task limit`() {
        val tasks = mockk<IndexAiTaskMapper>(relaxed = true)
        val ai = mockk<IndexFeedbackAiMapper>()
        val contents = mockk<IndexContentMapper>()
        val clusters = mockk<IndexClusterMapper>()
        val time = LocalDateTime.of(2026, 9, 2, 12, 0)
        val first = (1L..200L).map { IndexFeedbackAiPo(
            sourceId = it, aiProcessedAt = time, searchIndexStatus = "PENDING",
            vectorIndexStatus = "PENDING", indexMaskingRuleVersion = "old"
        ) }
        every { ai.findAfter(0, 200) } returns first
        every { ai.findAfter(200, 200) } returns listOf(IndexFeedbackAiPo(
            sourceId = 201, aiProcessedAt = time, searchIndexStatus = "PENDING",
            vectorIndexStatus = "PENDING", indexMaskingRuleVersion = "old"
        ))
        every { contents.findByIds(any()) } answers {
            firstArg<List<Long>>().map { IndexContentPo(it, maskingStatus = "PASS", updatedAt = time) }
        }
        every { tasks.findTaskKeys(match { it.size == 200 }) } answers { firstArg() }
        every { tasks.findTaskKeys(match { it.size == 1 }) } returns emptyList()
        every { clusters.findAfter(0, 200) } returns emptyList()
        every { tasks.insertOutbox(any()) } returns 1
        val repository = DerivedIndexRepository(tasks, ai, contents, mockk(relaxed = true), clusters, jacksonObjectMapper())

        repository.seed("rule-v2", "VECTOR_ON")

        verify(exactly = 1) { tasks.insertOutbox(match { it.single().sourceId == 201L }) }
    }

    @Test
    fun `claim returns the holder and generation that claim actually wrote`() {
        val tasks = mockk<IndexAiTaskMapper>(relaxed = true)
        val repository = repositoryWith(tasks)
        every { tasks.lockDue() } returns IndexAiTaskPo(id = 1, inputSnapshot = "{}", retryCount = 0)
        every { tasks.claim(1, "index-holder") } returns 1
        // claim 在数据库里自增 generation，回读才能拿到本次领取真正生效的 fencing token。
        every { tasks.findClaimed(1, "index-holder") } returns IndexAiTaskPo(
            id = 1, inputSnapshot = "{}", retryCount = 2, leaseHolder = "index-holder", leaseGeneration = 7
        )

        val claimed = repository.claim("index-holder")

        assertEquals("index-holder", claimed?.leaseHolder)
        assertEquals(7L, claimed?.leaseGeneration)
        assertEquals(2, claimed?.retryCount)
    }

    @Test
    fun `stale generation write back is reported as lease lost instead of an internal failure`() {
        val tasks = mockk<IndexAiTaskMapper>(relaxed = true)
        val repository = repositoryWith(tasks)
        // holder 相同但 generation 已被新租约推进：旧 Worker 的写回影响 0 行，属于正常路径。
        every { tasks.complete(1, "index-holder", 7) } returns 0
        every { tasks.fail(1, "index-holder", 7, "RETRY_PENDING", 1, false, 30L, "boom") } returns 0

        assertEquals(IndexTaskWriteOutcome.LEASE_LOST, repository.complete(1, "index-holder", 7))
        assertEquals(IndexTaskWriteOutcome.LEASE_LOST,
            repository.fail(1, "index-holder", 7, 1, terminal = false, delay = 30L, error = "boom"))
    }

    @Test
    fun `matched generation write back is applied`() {
        val tasks = mockk<IndexAiTaskMapper>(relaxed = true)
        val repository = repositoryWith(tasks)
        every { tasks.complete(1, "index-holder", 7) } returns 1
        every { tasks.fail(1, "index-holder", 7, "FAILED", 5, true, 0L, "boom") } returns 1

        assertEquals(IndexTaskWriteOutcome.APPLIED, repository.complete(1, "index-holder", 7))
        assertEquals(IndexTaskWriteOutcome.APPLIED,
            repository.fail(1, "index-holder", 7, 5, terminal = true, delay = 0L, error = "boom"))
    }

    private fun repositoryWith(tasks: IndexAiTaskMapper) = DerivedIndexRepository(
        tasks, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), jacksonObjectMapper()
    )
}
