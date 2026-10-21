package com.feedback.service.query

import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.repository.query.MetricGovernanceRepository
import com.feedback.repository.query.MetricReconciliationCounts
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricGovernanceQueryServiceTest {
    private val repository = mockk<MetricGovernanceRepository>()
    private val permissions = mockk<DataPermissionService>()
    private val service = MetricGovernanceQueryService(repository, permissions)

    private fun counts(aiReviewed: Long = 15) = MetricReconciliationCounts(250,200,20,15,15,180,140,15,
        aiReviewed,10,160,120,40)

    @Test fun `reconciles distinct sources inside the permission snapshot`() {
        every { permissions.scope() } returns DataAccessScope(setOf("JIRA"), setOf("CAMERA"), setOf("310000"))
        every { repository.reconciliation(any(),any()) } returns counts()

        val result = service.reconciliation("7d")

        assertEquals("PASSED", result["status"])
        assertEquals("metric-reconciliation-v3", result["metricVersion"])
        assertEquals("7d", result["range"])
        io.mockk.verify { repository.reconciliation(DataAccessScope(setOf("JIRA"),setOf("CAMERA"),setOf("310000")),any()) }
    }

    @Test fun `reports a missing AI outcome instead of hiding the mismatch`() {
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.reconciliation(any(),any()) } returns counts(aiReviewed = 14)

        val result = service.reconciliation("today")
        val checks = result["checks"] as List<*>
        val aiCheck = checks.filterIsInstance<Map<*, *>>()
            .single { it["check"] == "AI_OUTCOMES_RECONCILE_PROCESSED" }
        assertEquals("FAILED", result["status"])
        assertEquals(false, aiCheck["passed"])
    }

    @Test fun `rejects an unsupported range before querying data`() {
        val error = assertThrows(ClientException::class.java) { service.reconciliation("30d") }
        assertEquals("METRIC_RECONCILIATION_RANGE_INVALID", error.reasonCode)
    }
}
