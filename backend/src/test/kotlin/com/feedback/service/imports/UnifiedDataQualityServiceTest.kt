package com.feedback.service.imports

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.imports.UnifiedQualityFormalFacts
import com.feedback.repository.imports.UnifiedQualityImportFacts
import com.feedback.repository.imports.UnifiedQualityRepository
import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class UnifiedDataQualityServiceTest {
    @Test
    fun `channel duplicate rate is calculated from observed import rows and drives threshold`() {
        val repository=mockk<UnifiedQualityRepository>()
        every { repository.formalFacts(any(),any()) } returns perfectFormal(10)
        every { repository.importFacts(any()) } returns UnifiedQualityImportFacts(10,2,0,0)
        every { repository.insertSnapshot(any(),any(),any(),any(),any()) } returns 7L
        val alerts=mockk<DataQualityThresholdAlertPublisher>(relaxed=true)
        val policy=mockk<DataQualityThresholdPolicy>()
        every { policy.current() } returns DataQualityThresholds(primaryKeyDuplicateMax=.05)

        val result=UnifiedDataQualityService(repository,jacksonObjectMapper(),alerts,policy).refreshChannels()

        assertEquals("0.200000",result.first()["primaryKeyDuplicateRate"].toString())
        assertEquals("ALERT",result.first()["thresholdStatus"])
        assertTrue(result.all { it["primaryKeyDuplicateRate"].toString() == "0.200000" })
        assertTrue(result.any { it["channel"] == "STATION_FEEDBACK" })
        verify(exactly=5) { repository.importFacts(any()) }
        verify(exactly=5) { alerts.publish(any(),null,7L,any()) }
    }

    @Test
    fun `zero sample channel is not assigned a synthetic score or pass verdict`() {
        val repository=mockk<UnifiedQualityRepository>()
        every { repository.formalFacts(any(),any()) } returns perfectFormal(0)
        every { repository.importFacts(any()) } returns UnifiedQualityImportFacts(0,0,0,0)
        every { repository.insertSnapshot(any(),any(),any(),any(),any()) } returns 1L
        val alerts=mockk<DataQualityThresholdAlertPublisher>(relaxed=true)

        val result=UnifiedDataQualityService(repository,jacksonObjectMapper(),alerts,null).refreshChannels()

        assertTrue(result.all { it["totalCount"] == 0L })
        assertTrue(result.all { it["thresholdStatus"] == "NO_DATA" })
        assertTrue(result.all { it["qualityScore"] == null })
        assertTrue(result.all { it["requiredCompletenessRate"] == null })
        verify(exactly=0) { alerts.publish(any(),any(),any(),any()) }
    }

    @Test
    fun `current import quarantine contributes to channel total masking rate and anomaly count`() {
        val repository=mockk<UnifiedQualityRepository>()
        every { repository.formalFacts(any(),any()) } returns perfectFormal(80)
        every { repository.importFacts(any()) } returns UnifiedQualityImportFacts(100,0,20,0)
        every { repository.insertSnapshot(any(),any(),any(),any(),any()) } returns 8L

        val result=UnifiedDataQualityService(repository,jacksonObjectMapper(),null,null).refreshChannels().first()

        assertEquals(100L,result["totalCount"])
        assertEquals("0.800000",result["maskingPassRate"].toString())
        assertEquals(20L,result["quarantineCount"])
        assertEquals(20L,result["anomalyCount"])
    }

    @Test
    fun `import quarantine window excludes skipped duplicate and stale snapshots`() {
        val batches=mockk<UnifiedQualityBatchMapper>()
        val rows=mockk<UnifiedQualityImportRowMapper>()
        val now=LocalDateTime.now()
        every { batches.findActiveByChannel("JIRA") } returns listOf(UnifiedQualityBatchPo(1,now))
        every { rows.findObserved(listOf(1)) } returns listOf(
            UnifiedQualityImportRowPo(1,1,null,"SKIPPED_DUPLICATE",null),
            UnifiedQualityImportRowPo(2,1,null,"SKIPPED_STALE",null),
            UnifiedQualityImportRowPo(3,1,null,"FAILED",null)
        )
        every { rows.findCurrentCandidates(listOf(1)) } returns listOf(UnifiedQualityImportRowPo(3,1,null,"FAILED",null))
        val repository=UnifiedQualityRepository(mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),
            batches,rows,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))

        val facts=repository.importFacts("JIRA")

        assertEquals(3,facts.rowsSeen)
        assertEquals(1,facts.duplicateRows)
        assertEquals(1,facts.quarantinedRows)
        assertEquals(1,facts.accessFailureRows)
        verify(exactly=1) { rows.findCurrentCandidates(listOf(1)) }
    }

    private fun perfectFormal(total:Long)=UnifiedQualityFormalFacts(total,total,total,total,total,total,total,total,0,0,0,0)
}
