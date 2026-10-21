package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AlertMetricRepositoryTest {
    private val sources=mockk<AlertMetricSourceMapper>()
    private val contents=mockk<AlertMetricContentMapper>()
    private val identities=mockk<AlertMetricIdentityMapper>()
    private val links=mockk<AlertMetricClusterLinkMapper>()
    private val clusters=mockk<AlertMetricClusterMapper>()
    private val occurrences=mockk<AlertMetricOccurrenceMapper>()
    private val notifications=mockk<AlertMetricNotificationMapper>()
    private val batches=mockk<AlertMetricImportBatchMapper>()
    private val rows=mockk<AlertMetricImportRowMapper>()
    private val ingests=mockk<AlertMetricSdkIngestMapper>()
    private val configs=mockk<AlertMetricConfigMapper>()
    private val tasks=mockk<AlertMetricAiTaskMapper>()
    private val jira=mockk<AlertMetricJiraMapper>()
    private val sdk=mockk<AlertMetricSdkChannelMapper>()
    private val repository=AlertMetricRepository(sources,contents,identities,links,clusters,occurrences,notifications,batches,rows,ingests,configs,tasks,jira,sdk)

    @Test fun `contact escalation keeps distinct source and channel thresholds after table split`() {
        val now=LocalDateTime.now();val sourceRows=listOf(MetricSourcePo(1,"SDK",ingestTime=now),MetricSourcePo(2,"JIRA",ingestTime=now),MetricSourcePo(3,"SDK",ingestTime=now))
        every { sources.pageSince(1440,0,500) } returns sourceRows
        every { contents.bySources(listOf(1,2,3)) } returns sourceRows.map{MetricContentPo(it.id,"PASS",false)}
        every { identities.bySources(listOf(1,2,3)) } returns sourceRows.map{MetricIdentityPo(it.id,"same-person","PHONE")}
        every { links.mainBySources(listOf(1,2,3)) } returns sourceRows.map{MetricClusterLinkPo(it.id,9)}

        assertEquals(1.0,repository.contactEscalationCandidates())
        verify(exactly=0){links.activeBySources(any())};verify(exactly=0){sdk.bySources(any())}
    }

    @Test fun `error rate preserves joined valid content denominator`() {
        every { sources.pageSince(5,0,500) } returns listOf(MetricSourcePo(1,"SDK"),MetricSourcePo(2,"SDK"))
        every { contents.bySources(listOf(1,2)) } returns listOf(MetricContentPo(1,"PASS",false,"BUG"),MetricContentPo(1,"PASS",false,"OTHER"),MetricContentPo(2,"FAIL",false,"BUG"))

        assertEquals(50.0,repository.errorRate(5))
        verify(exactly=0){identities.bySources(any())};verify(exactly=0){links.mainBySources(any())};verify(exactly=0){sdk.bySources(any())}
    }

    @Test fun `persistent device recent threshold preserves original joined row sum`() {
        val source=MetricSourcePo(1,"SDK",ingestTime=LocalDateTime.now())
        every { sources.pageSince(10080,0,500) } returns listOf(source)
        every { contents.bySources(listOf(1)) } returns listOf(MetricContentPo(1,"PASS"),MetricContentPo(1,"PASS"),MetricContentPo(1,"PASS"))
        every { identities.bySources(listOf(1)) } returns listOf(MetricIdentityPo(1,"device-a","DEVICE","DEVICE"))
        every { links.mainBySources(listOf(1)) } returns listOf(MetricClusterLinkPo(1,4))

        assertEquals(1.0,repository.devicePersistentCandidates())
    }

    @Test fun `historical error rate uses exact half open source window content denominator`() {
        val start=LocalDateTime.of(2026,1,1,0,0);val end=start.plusHours(1)
        every { sources.pageBetween(start,end,0,500) } returns listOf(MetricSourcePo(7,"SDK"))
        every { contents.bySources(listOf(7)) } returns listOf(MetricContentPo(7,"PASS",false,"BUG"),MetricContentPo(7,"PASS",false,"OTHER"))
        assertEquals(50.0,repository.errorRate(start,end))
    }
}
