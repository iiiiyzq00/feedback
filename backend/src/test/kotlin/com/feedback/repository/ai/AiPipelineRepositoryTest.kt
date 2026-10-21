package com.feedback.repository.ai

import com.feedback.mapper.ai.*
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import com.feedback.common.exception.ServiceException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AiPipelineRepositoryTest {
    private val tasks = mockk<AiTaskMapper>(relaxed = true)
    private val sources = mockk<ClusterSourceTableMapper>(relaxed = true)
    private val contents = mockk<ClusterContentTableMapper>(relaxed = true)
    private val repository = AiPipelineRepository(
        tasks, sources, contents, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true)
    )

    @Test
    fun `claim cancels unavailable source facts before leasing first eligible task`() {
        every { tasks.findAvailabilityCandidates(0, 200) } returns listOf(
            AiTaskPo(id = 1, sourceId = 11, taskStatus = "PENDING"),
            AiTaskPo(id = 2, sourceId = 12, taskStatus = "PENDING")
        )
        every { sources.findByIds(listOf(11L, 12L)) } returns listOf(
            ClusterSourcePo(id = 11), ClusterSourcePo(id = 12)
        )
        every { contents.findByIds(listOf(11L, 12L)) } returns listOf(
            ClusterContentPo(sourceId = 11, maskingStatus = "FAILED"),
            ClusterContentPo(sourceId = 12, maskingStatus = "PASS", isDeleted = false)
        )
        every { tasks.lockClaimCandidates(1) } returns listOf(AiTaskPo(id = 2, sourceId = 12, retryCount = 1))
        every { tasks.claim(2, "worker") } returns 1
        every { tasks.findById(2) } returns AiTaskPo(
            id = 2, sourceId = 12, retryCount = 1, leaseHolder = "worker", leaseGeneration = 9
        )

        val claimed = repository.claim("worker")

        assertEquals(2L, claimed?.id)
        assertEquals(1, claimed?.retryCount)
        // claim 在数据库里自增 generation，领取方必须拿到回读后的值，否则写回无法通过 fencing 校验。
        assertEquals(9L, claimed?.leaseGeneration)
        verify(exactly = 1) { tasks.cancelUnavailable(listOf(1L)) }
    }

    @Test
    fun `write back carries the claimed generation and stale generation is rejected as lease lost`() {
        // generation=9 是本次领取的 fencing token，写回必须原样带上它。
        every { tasks.completeLease(2, "worker", 9, "{}", null) } returns 1
        repository.complete(2, "worker", 9, "{}", null)
        verify(exactly = 1) { tasks.completeLease(2, "worker", 9, "{}", null) }

        // 旧 generation 影响 0 行：租约已被接管，旧 Worker 的完成写回必须被拒绝而不是写入。
        every { tasks.completeLease(2, "worker", 9, "{}", "AI_DEGRADED") } returns 0
        assertThrows(ServiceException::class.java) {
            repository.complete(2, "worker", 9, "{}", "AI_DEGRADED")
        }

        every { tasks.retryFailure(2, "worker", 9, "IllegalStateException") } returns 1
        repository.retry(2, "worker", 9, "IllegalStateException", terminal = false)
        verify(exactly = 1) { tasks.retryFailure(2, "worker", 9, "IllegalStateException") }

        // 旧 generation 的重试写回同样落空；0 行在 retry 路径上本就允许（租约已被接管），不抛异常。
        every { tasks.terminalFailure(2, "worker", 9, "OutboundPrivacyException") } returns 0
        repository.retry(2, "worker", 9, "OutboundPrivacyException", terminal = true)
        verify(exactly = 1) { tasks.terminalFailure(2, "worker", 9, "OutboundPrivacyException") }
    }

    @Test
    fun `enqueue skips existing stable task keys and keeps scanning safe content pages`() {
        every { tasks.findCurrentPromptVersion("feedback_classify") } returns "classify-v3"
        every { contents.findEligibleAfter(0, 200) } returns listOf(
            ClusterContentPo(sourceId = 7, maskingStatus = "PASS"),
            ClusterContentPo(sourceId = 8, maskingStatus = "PASS")
        )
        every { tasks.findTaskKeys(any()) } returns listOf("feedback_classify:7:classify-v3")

        assertEquals(listOf(8L), repository.enqueueSourceIds(100))
    }
}
