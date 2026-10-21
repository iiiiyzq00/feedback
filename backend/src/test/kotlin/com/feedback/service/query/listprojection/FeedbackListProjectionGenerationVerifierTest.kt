package com.feedback.service.query.listprojection

import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListContentFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListSourceFactsPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionAuxiliaryFacts
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackListProjectionGenerationVerifierTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val assembler = FeedbackListProjectionAssembler(mapper)

    @Test
    fun `verification normalizes JSON and reconciles bounded authoritative and projection scans`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        val source = source()
        val content = content()
        val auxiliary = emptyAuxiliary()
        val expected = assembler.assemble(7, source, content, auxiliary).projection
        val stored = expected.copy(diagnosticTypes = "[ ]")
        stubSingleRow(repository, source, content, auxiliary, stored)

        val result = FeedbackListProjectionGenerationVerifier(repository, assembler, mapper).verify(7, 1, 1, 4)

        assertEquals(1L, result.projectedSourceCount)
        assertEquals(0L, result.rejectedSourceCount)
        assertEquals(64, result.contentChecksum.length)
    }

    @Test
    fun `verification fails closed when projected business content differs`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        val source = source()
        val content = content()
        val auxiliary = emptyAuxiliary()
        val stored = assembler.assemble(7, source, content, auxiliary).projection.copy(title = "different")
        stubSingleRow(repository, source, content, auxiliary, stored)

        assertThrows(ServiceException::class.java) {
            FeedbackListProjectionGenerationVerifier(repository, assembler, mapper).verify(7, 1, 1, 4)
        }
    }

    @Test
    fun `verification reads the application dictionary once across keyset batches`() {
        val repository = mockk<FeedbackListProjectionRepository>()
        val firstSource = source()
        val secondSource = source().copy(id = 2, sourceRecordId = "SDK-2")
        val firstContent = content()
        val secondContent = content().copy(sourceId = 2, title = "标题2")
        val names = listOf("课堂")
        val auxiliary = emptyAuxiliary().copy(applicationDictionary = names)
        val stored = listOf(
            assembler.assemble(7, firstSource, firstContent, auxiliary).projection,
            assembler.assemble(7, secondSource, secondContent, auxiliary).projection
        )
        every { repository.nextSourceIds(0, 2, 200) } returns listOf(1)
        every { repository.nextSourceIds(1, 2, 200) } returns listOf(2)
        every { repository.nextSourceIds(2, 2, 200) } returns emptyList()
        every { repository.sources(listOf(1)) } returns mapOf(1L to firstSource)
        every { repository.sources(listOf(2)) } returns mapOf(2L to secondSource)
        every { repository.contents(listOf(1)) } returns mapOf(1L to firstContent)
        every { repository.contents(listOf(2)) } returns mapOf(2L to secondContent)
        every { repository.applicationNames() } returns names
        every { repository.auxiliary(listOf(firstSource), names) } returns auxiliary
        every { repository.auxiliary(listOf(secondSource), names) } returns auxiliary
        every { repository.projectionPage(7, 0, 200) } returns stored
        every { repository.projectionPage(7, 2, 200) } returns emptyList()
        every { repository.memberPage(7, 0, 200) } returns emptyList()
        every { repository.identityPage(7, 0, "", 200) } returns emptyList()

        val result = FeedbackListProjectionGenerationVerifier(repository, assembler, mapper).verify(7, 2, 2, 4)

        assertEquals(2, result.projectedSourceCount)
        verify(exactly = 1) { repository.applicationNames() }
        verify(exactly = 2) { repository.auxiliary(any(), names) }
    }

    private fun stubSingleRow(
        repository: FeedbackListProjectionRepository,
        source: FeedbackListSourceFactsPo,
        content: FeedbackListContentFactsPo,
        auxiliary: FeedbackListProjectionAuxiliaryFacts,
        stored: com.feedback.model.po.feedback.listprojection.FeedbackListProjectionPo
    ) {
        every { repository.nextSourceIds(0, 1, 200) } returns listOf(1)
        every { repository.nextSourceIds(1, 1, 200) } returns emptyList()
        every { repository.sources(listOf(1)) } returns mapOf(1L to source)
        every { repository.contents(listOf(1)) } returns mapOf(1L to content)
        every { repository.applicationNames() } returns emptyList()
        every { repository.auxiliary(listOf(source), emptyList()) } returns auxiliary
        every { repository.projectionPage(7, 0, 200) } returns listOf(stored)
        every { repository.projectionPage(7, 1, 200) } returns emptyList()
        every { repository.memberPage(7, 0, 200) } returns emptyList()
        every { repository.identityPage(7, 0, "", 200) } returns emptyList()
    }

    private fun source() = FeedbackListSourceFactsPo(
        id = 1, sourceChannel = "SDK", sourceRecordId = "SDK-1", sourceEventTime = NOW,
        ingestTime = NOW, dataEnvironment = "PRODUCTION", updatedAt = NOW
    )

    private fun content() = FeedbackListContentFactsPo(
        sourceId = 1, title = "标题", processingStatus = "OPEN", maskingStatus = "PASS", updatedAt = NOW
    )

    private fun emptyAuxiliary() = FeedbackListProjectionAuxiliaryFacts(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
    )

    companion object { val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 30, 10, 0) }
}
