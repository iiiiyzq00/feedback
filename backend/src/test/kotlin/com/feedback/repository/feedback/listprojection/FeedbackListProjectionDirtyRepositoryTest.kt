package com.feedback.repository.feedback.listprojection

import com.feedback.common.exception.ClientException
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionDirtyMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyVersionPo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackListProjectionDirtyRepositoryTest {
    private val mapper = mockk<FeedbackListProjectionDirtyMapper>(relaxed = true)
    private val repository = FeedbackListProjectionDirtyRepository(mapper)

    @Test
    fun `mark sorts and deduplicates a bounded batch`() {
        every { mapper.upsertDirty(listOf(1, 2), "CONTENT") } returns 2
        repository.mark(listOf(2, 1, 2), FeedbackListProjectionDirtyReason.CONTENT)
        verify(exactly = 1) { mapper.upsertDirty(listOf(1, 2), "CONTENT") }
    }

    @Test
    fun `claim preserves mutation versions for conditional acknowledgement`() {
        val rows = listOf(
            FeedbackListProjectionDirtyPo(sourceId = 1, mutationVersion = 4),
            FeedbackListProjectionDirtyPo(sourceId = 2, mutationVersion = 7)
        )
        val versions = listOf(FeedbackListProjectionDirtyVersionPo(1, 4), FeedbackListProjectionDirtyVersionPo(2, 7))
        every { mapper.findClaimableForUpdate(listOf(1, 2), 200) } returns rows
        every { mapper.markProcessing(versions, "worker", 60) } returns 2

        val claim = repository.claim(listOf(2, 1), 200, "worker", 60)!!

        assertEquals(listOf(1L, 2L), claim.sourceIds)
        assertEquals(versions, claim.versions)
        verify(exactly = 1) { mapper.markProcessing(versions, "worker", 60) }
    }

    @Test
    fun `empty and oversized commands fail before mapper access`() {
        assertThrows(ClientException::class.java) {
            repository.mark(emptyList(), FeedbackListProjectionDirtyReason.SOURCE)
        }
        assertThrows(ClientException::class.java) {
            repository.mark((1L..201L).toList(), FeedbackListProjectionDirtyReason.SOURCE)
        }
        assertThrows(ClientException::class.java) {
            repository.claim(null, 201, "worker", 60)
        }
        verify(exactly = 0) { mapper.upsertDirty(any(), any()) }
        verify(exactly = 0) { mapper.findClaimableForUpdate(any(), any()) }
    }

    @Test
    fun `acknowledgement delegates version and lease compare and swap`() {
        val row = FeedbackListProjectionDirtyPo(sourceId = 9, mutationVersion = 3)
        val claim = FeedbackListProjectionDirtyClaim("worker", listOf(row))
        every { mapper.deleteClaimed(claim.versions, "worker") } returns 0
        assertEquals(0, repository.acknowledge(claim))
        verify(exactly = 1) { mapper.deleteClaimed(listOf(FeedbackListProjectionDirtyVersionPo(9, 3)), "worker") }
    }
}
