package com.feedback.service.query.listprojection

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.mapper.feedback.listprojection.*
import com.feedback.model.po.feedback.listprojection.*
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionAuxiliaryFacts
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import com.feedback.common.exception.ServiceException
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import java.time.LocalDateTime

class FeedbackListProjectionSynchronizerTest {
    private val now = LocalDateTime.of(2026, 8, 29, 11, 0)

    @Test
    fun `unsafe rows are rejected before auxiliary facts are requested`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        val assembler = FeedbackListProjectionAssembler(jacksonObjectMapper())
        val synchronizer = FeedbackListProjectionSynchronizer(repository, assembler)
        val safeSource = source(1)
        val unsafeSource = source(2)
        val safeContent = content(1)
        val unsafeContent = content(2).copy(maskingStatus = "PENDING")
        val empty = emptyAuxiliary()
        every { repository.lockBuilding(5) } returns FeedbackListProjectionGenerationPo(id = 5, buildFenceSourceId = 2)
        every { repository.sources(listOf(1, 2)) } returns mapOf(1L to safeSource, 2L to unsafeSource)
        every { repository.contents(listOf(1, 2)) } returns mapOf(1L to safeContent, 2L to unsafeContent)
        every { repository.auxiliary(listOf(safeSource)) } returns empty
        every { repository.replaceBatch(5, listOf(1, 2), any(), any()) } just Runs
        every { repository.advanceBuild(5, 2, 1, 1) } just Runs

        val result = synchronizer.refresh(FeedbackListProjectionRefreshCommand(5, listOf(1, 2)))

        assertEquals(1, result.projectedCount)
        assertEquals(1, result.rejectedCount)
        verifyOrder {
            repository.lockBuilding(5)
            repository.sources(listOf(1, 2))
            repository.contents(listOf(1, 2))
            repository.auxiliary(listOf(safeSource))
            repository.replaceBatch(5, listOf(1, 2), any(), any())
            repository.advanceBuild(5, 2, 1, 1)
        }
    }

    @Test
    fun `cursor must advance monotonically`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        every { repository.lockBuilding(5) } returns FeedbackListProjectionGenerationPo(id = 5, buildCursorSourceId = 10)
        val synchronizer = FeedbackListProjectionSynchronizer(repository, FeedbackListProjectionAssembler(jacksonObjectMapper()))
        assertThrows(ServiceException::class.java) {
            synchronizer.refresh(FeedbackListProjectionRefreshCommand(5, listOf(10, 11)))
        }
        verify(exactly = 0) { repository.sources(any()) }
    }

    @Test
    fun `incremental refresh loads safe facts once and replaces every writable generation`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        val assembler = FeedbackListProjectionAssembler(jacksonObjectMapper())
        val synchronizer = FeedbackListProjectionSynchronizer(repository, assembler)
        val safeSource = source(1)
        val unsafeSource = source(2)
        val safeContent = content(1)
        val unsafeContent = content(2).copy(maskingStatus = "PENDING")
        every { repository.lockWritableGenerations() } returns listOf(
            FeedbackListProjectionGenerationPo(id = 5, generationStatus = "ACTIVE"),
            FeedbackListProjectionGenerationPo(id = 6, generationStatus = "BUILDING", buildFenceSourceId = 2)
        )
        every { repository.sources(listOf(1, 2)) } returns mapOf(1L to safeSource, 2L to unsafeSource)
        every { repository.contents(listOf(1, 2)) } returns mapOf(1L to safeContent, 2L to unsafeContent)
        every { repository.auxiliary(listOf(safeSource)) } returns emptyAuxiliary()
        every { repository.replaceBatch(any(), listOf(1, 2), any(), any()) } just Runs

        val result = synchronizer.refreshIncremental(FeedbackListProjectionIncrementalRefreshCommand(listOf(2, 1)))

        assertEquals(listOf(5L, 6L), result.handledGenerationIds)
        assertEquals(1, result.projectedCount)
        assertEquals(1, result.rejectedCount)
        verify(exactly = 1) { repository.auxiliary(listOf(safeSource)) }
        verify(exactly = 1) { repository.replaceBatch(5, listOf(1, 2), match { it.size == 1 }, any()) }
        verify(exactly = 1) { repository.replaceBatch(6, listOf(1, 2), match { it.size == 1 }, any()) }
        verify(exactly = 0) { repository.advanceBuild(any(), any(), any(), any()) }
    }

    @Test
    fun `incremental refresh without writable generation leaves facts unread`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        every { repository.lockWritableGenerations() } returns emptyList()
        val synchronizer = FeedbackListProjectionSynchronizer(repository, FeedbackListProjectionAssembler(jacksonObjectMapper()))

        val result = synchronizer.refreshIncremental(FeedbackListProjectionIncrementalRefreshCommand(listOf(1)))

        assertEquals(false, result.hasWritableGeneration)
        verify(exactly = 0) { repository.sources(any()) }
        verify(exactly = 0) { repository.contents(any()) }
        verify(exactly = 0) { repository.auxiliary(any()) }
    }

    @Test
    fun `mixed dirty batch refreshes all active ids and only in fence building ids`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        every { repository.lockWritableGenerations() } returns listOf(
            FeedbackListProjectionGenerationPo(id = 5, generationStatus = "ACTIVE"),
            FeedbackListProjectionGenerationPo(id = 6, generationStatus = "BUILDING", buildFenceSourceId = 10)
        )
        every { repository.sources(listOf(9, 11)) } returns mapOf(9L to source(9), 11L to source(11))
        every { repository.contents(listOf(9, 11)) } returns mapOf(9L to content(9), 11L to content(11))
        every { repository.auxiliary(listOf(source(9), source(11))) } returns emptyAuxiliary()
        every { repository.replaceBatch(any(), any(), any(), any()) } just Runs
        val synchronizer = FeedbackListProjectionSynchronizer(repository, FeedbackListProjectionAssembler(jacksonObjectMapper()))

        val result = synchronizer.refreshIncremental(FeedbackListProjectionIncrementalRefreshCommand(listOf(11, 9)))

        assertEquals(1, result.deferredToBuildKeysetCount)
        assertEquals(listOf(5L, 6L), result.handledGenerationIds)
        verify(exactly = 1) { repository.replaceBatch(5, listOf(9, 11), match { it.size == 2 }, any()) }
        verify(exactly = 1) { repository.replaceBatch(6, listOf(9), match { it.size == 1 }, any()) }
    }

    @Test
    fun `building only dirty beyond fence is acknowledged for deterministic keyset catch up without fact reads`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        every { repository.lockWritableGenerations() } returns listOf(
            FeedbackListProjectionGenerationPo(id = 6, generationStatus = "BUILDING", buildFenceSourceId = 10)
        )
        val synchronizer = FeedbackListProjectionSynchronizer(repository, FeedbackListProjectionAssembler(jacksonObjectMapper()))

        val result = synchronizer.refreshIncremental(FeedbackListProjectionIncrementalRefreshCommand(listOf(11)))

        assertEquals(true, result.hasWritableGeneration)
        assertEquals(1, result.deferredToBuildKeysetCount)
        verify(exactly = 0) { repository.sources(any()) }
        verify(exactly = 0) { repository.contents(any()) }
        verify(exactly = 0) { repository.replaceBatch(any(), any(), any(), any()) }
    }

    @Test
    fun `coordinator owns the transaction boundary`() {
        val refresh = FeedbackListProjectionMutationCoordinator::class.java
            .getDeclaredMethod("refreshNextBatch", FeedbackListProjectionBuildCommand::class.java)
        val catchUp = FeedbackListProjectionMutationCoordinator::class.java
            .getDeclaredMethod("prepareBuildingCatchUp", FeedbackListProjectionBuildCommand::class.java)
        val create = FeedbackListProjectionMutationCoordinator::class.java
            .getDeclaredMethod("createBuildingSnapshot", FeedbackListProjectionBuildCommand::class.java)
        assertEquals(Propagation.REQUIRES_NEW, refresh.getAnnotation(Transactional::class.java).propagation)
        assertEquals(Isolation.REPEATABLE_READ, refresh.getAnnotation(Transactional::class.java).isolation)
        assertEquals(Propagation.REQUIRES_NEW, catchUp.getAnnotation(Transactional::class.java).propagation)
        assertEquals(Isolation.REPEATABLE_READ, catchUp.getAnnotation(Transactional::class.java).isolation)
        check(create.isAnnotationPresent(Transactional::class.java))
        val incremental = FeedbackListProjectionMutationCoordinator::class.java
            .getDeclaredMethod("refreshIncremental", FeedbackListProjectionIncrementalRefreshCommand::class.java)
        assertEquals(Isolation.REPEATABLE_READ, incremental.getAnnotation(Transactional::class.java).isolation)
        val acknowledge = FeedbackListProjectionMutationCoordinator::class.java
            .getDeclaredMethod(
                "acknowledgeDirty",
                com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyClaim::class.java
            )
        assertEquals(Propagation.REQUIRES_NEW, acknowledge.getAnnotation(Transactional::class.java).propagation)
    }

    private fun source(id: Long) = FeedbackListSourceFactsPo(
        id = id, sourceChannel = "SDK", sourceRecordId = "SDK-$id", sourceEventTime = now,
        ingestTime = now, dataEnvironment = "PRODUCTION", updatedAt = now
    )
    private fun content(id: Long) = FeedbackListContentFactsPo(
        sourceId = id, title = "标题$id", processingStatus = "OPEN", maskingStatus = "PASS", updatedAt = now
    )
    private fun emptyAuxiliary() = FeedbackListProjectionAuxiliaryFacts(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
    )
}
