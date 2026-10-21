package com.feedback.service.report

import com.feedback.common.exception.ClientException
import com.feedback.mapper.report.*
import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import com.feedback.repository.report.ClusterReportRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ClusterReportServiceTest {
    private val repository = mockk<ClusterReportRepository>()
    private val permissions = mockk<DataPermissionService>()
    private val service = ClusterReportService(repository, permissions)
    private val time = LocalDateTime.of(2026, 9, 2, 10, 0)

    @Test
    fun `rca keeps scoped cluster visibility and complete rca fields`() {
        visibleCluster()
        every { repository.causeLinks(1) } returns listOf(ClusterReportCauseLinkPo(1, 9, 8))
        every { repository.rcas(listOf(9L)) } returns listOf(
            ClusterReportRcaPo(9, 8, "根因", "QUICK", "ALERT", "连接泄漏", "修复连接池", "PUBLISHED", 0.9, time),
        )

        val rows = service.rca(1)

        assertEquals("连接泄漏", rows.single()["rootCause"])
        assertEquals("修复连接池", rows.single()["solution"])
        verify(exactly = 1) { repository.sourceLinks(1) }
    }

    @Test
    fun `feedback projection reuses one visibility snapshot for cluster rca and knowledge`() {
        visibleCluster()
        every { repository.mainLinksForSource(100) } returns listOf(ClusterReportLinkPo(1, 100))
        every { repository.causeLinks(1) } returns listOf(ClusterReportCauseLinkPo(1, 9, 8))
        every { repository.rcas(listOf(9L)) } returns listOf(ClusterReportRcaPo(id = 9, title = "根因", createdAt = time))
        every { repository.knowledge(listOf(9L)) } returns listOf(ClusterReportKnowledgePo(id = 10, title = "知识", createdAt = time))

        val result = service.feedbackRcaKnowledge(100)

        assertEquals(1L, (result["cluster"] as Map<*, *>)["id"])
        assertEquals(1, (result["rca"] as List<*>).size)
        assertEquals(1, (result["knowledge"] as List<*>).size)
        verify(exactly = 1) { repository.sourceLinks(1) }
        verify(exactly = 1) { repository.causeLinks(1) }
    }

    @Test
    fun `empty channel scope fails closed before cluster data is read`() {
        every { permissions.scope() } returns DataAccessScope(emptySet(), null, null)

        assertThrows(ClientException::class.java) { service.rca(1) }

        verify(exactly = 0) { repository.cluster(any()) }
    }

    private fun visibleCluster() {
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), setOf("LEARNING"), setOf("CN-11"))
        every { repository.cluster(1) } returns ClusterReportClusterPo(id = 1, name = "学习应用卡顿")
        every { repository.sourceLinks(1) } returns listOf(ClusterReportLinkPo(1, 100))
        every { repository.sources(listOf(100L)) } returns listOf(ClusterReportSourcePo(100, "SDK", time, "CN-11", null))
        every { repository.contents(listOf(100L)) } returns listOf(ClusterReportContentPo(100, "卡顿", "进入课程卡顿", "LEARNING", "BUG"))
        every { repository.identities(listOf(100L)) } returns emptyList()
    }
}
