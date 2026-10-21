package com.feedback.service.imports

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import com.feedback.repository.imports.ImportQualityRepository
import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ImportQualityServiceTest {
    private val service = ImportQualityService(mockk<ImportQualityRepository>(), jacksonObjectMapper())

    @Test
    fun `record score is versioned deterministic and bounded`() {
        val perfect = service.score(FeedbackQualityInput("J-1", "模拟反馈", emptyList(), "PASS", LocalDateTime.now()))
        val degraded = service.score(FeedbackQualityInput("J-2", "模拟反馈", listOf("UNMAPPED_PRIORITY"), "PASS", null))

        assertEquals("1.000000", perfect.toPlainString())
        assertTrue(degraded < perfect)
        assertTrue(degraded.signum() >= 0)
        assertEquals("quality-score-v1", ImportQualityService.VERSION)
    }

    @Test
    fun `batch mapping denominator only includes sources represented by content`() {
        val batches=mockk<ImportQualityBatchMapper>()
        val rows=mockk<ImportQualityRowMapper>()
        val sources=mockk<ImportQualitySourceMapper>()
        val contents=mockk<ImportQualityContentMapper>()
        val links=mockk<ImportQualityLinkMapper>()
        every { batches.findBase(7) } returns ImportQualityBatchPo(7,2,"JIRA",LocalDateTime.now())
        every { sources.findByBatch(7) } returns listOf(
            ImportQualitySourcePo(1,"JIRA","pkg.one","1"),
            ImportQualitySourcePo(2,"JIRA","pkg.two","2")
        )
        every { contents.findBySourceIds(listOf(1,2)) } returns listOf(ImportQualityContentPo(1,"module"))
        every { links.findActiveBySourceIds(listOf(1,2)) } returns emptyList()
        every { rows.counts(7) } returns ImportQualityRowCountsPo(2,2,2,0,0,0,0,0,0,0)
        every { rows.attachmentCounts(7) } returns ImportQualityAttachmentCountsPo(0,0)

        val facts=ImportQualityRepository(batches,rows,sources,contents,links).facts(7)

        assertEquals(1,facts.recordCount)
        assertEquals(1,facts.appMapped)
        assertEquals(1,facts.moduleMapped)
        assertEquals(1,facts.versionMapped)
    }
}
