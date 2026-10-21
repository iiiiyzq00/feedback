package com.feedback.repository.query

import com.feedback.mapper.query.MetricAiMapper
import com.feedback.mapper.query.MetricClusterLinkMapper
import com.feedback.mapper.query.MetricContentMapper
import com.feedback.mapper.query.MetricDefinitionMapper
import com.feedback.mapper.query.MetricSourceMapper
import com.feedback.model.po.query.MetricAiFactPo
import com.feedback.model.po.query.MetricClusterFactPo
import com.feedback.model.po.query.MetricContentFactPo
import com.feedback.model.po.query.MetricDefinitionPo
import com.feedback.model.auth.DataAccessScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class MetricGovernanceRepositoryTest {
    private val definitions = mockk<MetricDefinitionMapper>()
    private val sources = mockk<MetricSourceMapper>()
    private val contents = mockk<MetricContentMapper>()
    private val ai = mockk<MetricAiMapper>()
    private val links = mockk<MetricClusterLinkMapper>()
    private val repository = MetricGovernanceRepository(definitions, sources, contents, ai, links)
    private val start = LocalDateTime.of(2026, 9, 5, 0, 0)

    @Test
    fun `one short reconciliation page uses exactly four fact queries`() {
        val scope = DataAccessScope(setOf("JIRA"), setOf("COURSE"), null)
        every { sources.findIdsAfter(start, setOf("JIRA"), null, 0, 500) } returns listOf(11)
        every { contents.findActive(listOf(11), setOf("COURSE")) } returns listOf(MetricContentFactPo(11, "PASS"))
        every { ai.findProcessed(listOf(11)) } returns listOf(MetricAiFactPo(11, "NOT_REQUIRED"))
        every { links.findActiveMain(listOf(11)) } returns listOf(MetricClusterFactPo(11, "EXISTING"))

        val result = repository.reconciliation(scope, start)

        assertEquals(1, result.received)
        assertEquals(1, result.aiProcessed)
        assertEquals(1, result.clusterDecided)
        verify(exactly = 1) { sources.findIdsAfter(any(), any(), any(), any(), 500) }
        verify(exactly = 1) { contents.findActive(any(), any()) }
        verify(exactly = 1) { ai.findProcessed(any()) }
        verify(exactly = 1) { links.findActiveMain(any()) }
    }

    @Test
    fun `page without privacy pass skips AI and cluster queries`() {
        val scope = DataAccessScope(setOf("SDK"), null, null)
        every { sources.findIdsAfter(start, setOf("SDK"), null, 0, 500) } returns listOf(12)
        every { sources.findIdsAfter(start, setOf("SDK"), null, 12, 500) } returns emptyList()
        every { contents.findActive(listOf(12), null) } returns listOf(MetricContentFactPo(12, "REVIEW_NEEDED"))

        repository.reconciliation(scope, start)

        verify(exactly = 0) { ai.findProcessed(any()) }
        verify(exactly = 0) { links.findActiveMain(any()) }
    }

    @Test
    fun `metric definition list is bounded by a sentinel`() {
        every { definitions.findActive(501) } returns List(501) { MetricDefinitionPo(metricCode = "M$it") }

        assertThrows(IllegalStateException::class.java) { repository.definitions() }
        verify(exactly = 1) { definitions.findActive(501) }
    }
}
