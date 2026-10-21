package com.feedback.service.cluster

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.RuntimeConfigService
import com.feedback.config.RuntimeStringDictionary
import com.feedback.model.cluster.Cluster
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import java.math.BigDecimal
import java.time.LocalDateTime

class ClusterReviewGovernanceTest {
    private val config=mockk<RuntimeConfigService>().also { config ->
        every { config.int("cluster.review-hotspot-min-channels",2) } returns 2
        every { config.int("cluster.review-hotspot-min-feedback",5) } returns 5
        every { config.int("cluster.review-sla-p1-hours",4) } returns 4
        every { config.int("cluster.review-sla-p2-business-days",1) } returns 1
        every { config.int("cluster.review-sla-p3-business-days",3) } returns 3
        every { config.decimal("cluster.competition-margin",any()) } returns BigDecimal("0.03")
        every { config.dictionary("cluster.review-holidays") } returns RuntimeStringDictionary.EMPTY
    }
    private val governance=ClusterReviewGovernance(config,jacksonObjectMapper(),slaEnabled=true)
    private val monday=LocalDateTime.of(2026,8,3,10,0)

    @Test fun `S0 and S1 are P1 while S2 is P2`() {
        assertEquals("P1",governance.assign(profile("S0"),emptyList(),monday).priority)
        assertEquals("P1",governance.assign(profile("s1"),emptyList(),monday).priority)
        assertEquals("P2",governance.assign(profile("S2"),emptyList(),monday).priority)
    }

    @Test fun `policy contract distinguishes severity from review priority and exposes governed thresholds`() {
        val policy=governance.policy()
        assertEquals("cluster-review-priority-v1",policy.version)
        assertTrue(policy.severityRelationship.contains("不得反向修改S"))
        assertEquals(BigDecimal("0.03"),policy.competitionMargin)
        assertEquals(2,policy.hotspotMinChannels)
        assertEquals(5,policy.hotspotMinFeedback)
        assertTrue(policy.slaEnabled)
        assertEquals(mapOf("P1" to "4小时","P2" to "1工作日","P3" to "3工作日"),policy.sla)
    }

    @Test fun `development mode keeps review priority while disabling SLA wording`() {
        val policy=ClusterReviewGovernance(config,jacksonObjectMapper(),slaEnabled=false).policy()
        assertEquals(false,policy.slaEnabled)
        assertEquals(setOf("开发阶段不计时"),policy.sla.values.toSet())
    }

    @Test fun `S3 S4 and unknown severity remain normal P3 without rewriting severity`() {
        listOf("S3","S4",null,"UNKNOWN").forEach { severity ->
            val assignment=governance.assign(profile(severity),emptyList(),monday)
            assertEquals("P3",assignment.priority)
            assertEquals(severity,profile(severity).severityLevel)
        }
    }

    @Test fun `resolved recurrence is P2`() {
        assertEquals("P2",governance.assign(profile("S3"),listOf(candidate(1,"0.80",recurrence=true)),monday).priority)
    }

    @Test fun `cross channel candidate needs both volume and close competition`() {
        val close=listOf(candidate(1,"0.82",count=5,channels="{\"SDK\":3,\"JIRA\":2}"),candidate(2,"0.80"))
        assertEquals("P2",governance.assign(profile("S3"),close,monday).priority)
        assertEquals("P3",governance.assign(profile("S3"),listOf(candidate(1,"0.82",count=4,channels="{\"SDK\":2,\"JIRA\":2}"),candidate(2,"0.80")),monday).priority)
        assertEquals("P3",governance.assign(profile("S3"),listOf(candidate(1,"0.90",count=5,channels="{\"SDK\":3,\"JIRA\":2}"),candidate(2,"0.80")),monday).priority)
    }

    @Test fun `business day SLA skips weekend`() {
        val friday=LocalDateTime.of(2026,8,7,10,0)
        assertEquals(LocalDateTime.of(2026,8,10,10,0),governance.assign(profile("S2"),emptyList(),friday).dueAt)
        assertEquals(LocalDateTime.of(2026,8,12,10,0),governance.assign(profile("S3"),emptyList(),friday).dueAt)
    }

    private fun profile(severity:String?)=ProblemProfile("PRODUCT",null,null,null,"symptom",null,null,null,null,null,null,null,severity,"SDK",null,emptyMap())
    private fun candidate(id:Long,score:String,count:Int=0,channels:String="{}",recurrence:Boolean=false)=CandidateEvidence(
        Cluster(id=id,clusterName="cluster-$id",totalRecordCount=count,channelDistribution=channels),BigDecimal(score),historicalRecurrence=recurrence
    )
}
