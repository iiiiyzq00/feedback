package com.feedback.service.cluster

import com.feedback.audit.BusinessAuditService
import com.feedback.common.exception.ClientException
import com.feedback.model.po.cluster.ClusterLinkPo
import com.feedback.model.po.cluster.ClusterReclassificationRunPo
import com.feedback.repository.cluster.ClusterReclassificationCoverage
import com.feedback.repository.cluster.ClusterReclassificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DeadlockLoserDataAccessException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.UUID

class ClusterReclassificationServiceTest {
    @Test
    fun `start is idempotent and snapshots before link replacement`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val requestId = UUID.randomUUID()
        val row = runRow(7L, requestId.toString())
        every { repository.byRequestId(requestId.toString()) } returnsMany listOf(null, row)
        every { repository.safeCount() } returns 0L
        every { repository.create(any(), requestId.toString(), "重建问题簇边界", 9L, 0L) } returns 7L
        every { repository.run(7L) } returns row
        val service = service(repository)

        val first = service.start(requestId, "重建问题簇边界", 9L)
        val second = service.start(requestId, "不同理由但相同请求", 10L)

        assertEquals(7L, first.id)
        assertEquals("RUNNING", second.runStatus)
        verify(exactly = 1) { repository.snapshotAndIsolate(7L, 9L) }
        verify(exactly = 1) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start rejects assisted AI mode before changing state`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val service = service(repository, aiEnabled = true)
        assertThrows(ClientException::class.java) {
            service.start(UUID.randomUUID(), "禁止 AI 辅助时重分类", 1L)
        }
        verify(exactly = 0) { repository.create(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rollback deactivates unowned active main before restoring old main`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val run = runRow(7L, UUID.randomUUID().toString())
        every { repository.lockRun(7L) } returns run
        every { repository.run(7L) } returns run.copy(runStatus = "ROLLED_BACK")
        val service = service(repository)

        val result = service.rollback(7L, "撤回本次重分类", 9L)

        assertEquals("ROLLED_BACK", result.runStatus)
        verify(exactly = 1) { repository.beginRollback(7L, any()) }
        verify(exactly = 1) { repository.rollbackLinks(7L) }
        verify(exactly = 1) { repository.restoreSnapshots(7L) }
        verify(exactly = 1) { repository.completeRollback(7L) }
    }

    @Test
    fun `transient batch rollback leaves run claimable instead of failed`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        every { repository.claim(any()) } returns 7L
        every { repository.nextUnlinkedSafeSources(100) } throws
            DeadlockLoserDataAccessException("deadlock", IllegalStateException("try again"))
        val service = service(repository)

        assertEquals(false, service.processBatch())
        verify(exactly = 0) { repository.fail(any(), any(), any()) }
    }

    @Test
    fun `successful batch processes and commits every source through MyBatis boundary`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val engine = mockk<ClusteringEngine>()
        every { repository.claim(any()) } returns 7L
        every { repository.nextUnlinkedSafeSources(100) } returns listOf(101L, 102L)
        every { engine.cluster(any(), 7L) } returns
            ClusterDecision(1L, "AUTO_HIGH", java.math.BigDecimal.ONE, "NOT_REQUIRED")
        val service = service(repository, engine = engine)

        assertTrue(service.processBatch())
        verify(exactly = 1) { engine.cluster(101L, 7L) }
        verify(exactly = 1) { engine.cluster(102L, 7L) }
        verify(exactly = 1) { repository.refreshCounts(7L) }
        verify(exactly = 1) { repository.renew(7L, any()) }
    }

    @Test
    fun `non transient batch failure marks run failed`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        every { repository.claim(any()) } returns 7L
        every { repository.nextUnlinkedSafeSources(100) } throws DataIntegrityViolationException("permanent")
        val service = service(repository)

        assertEquals(false, service.processBatch())
        verify(exactly = 1) { repository.fail(7L, any(), match { it.contains("CLUSTER_RECLASSIFICATION") }) }
    }

    @Test
    fun `successful finalization archives every operational cluster without active main`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        every { repository.claim(any()) } returns 7L
        every { repository.nextUnlinkedSafeSources(100) } returns emptyList()
        every { repository.coverage(7L) } returns ClusterReclassificationCoverage(0, 0, 0, 0, 0)
        val service = service(repository)

        assertTrue(service.processBatch())
        verify(exactly = 1) {
            repository.archiveEveryOperationalWithoutActiveMain(
                "全量重分类后无活动 MAIN 关联", any(), clearStatistics = true
            )
        }
        verify(exactly = 1) { repository.complete(7L, any()) }
    }

    @Test
    fun `name quality repair isolates owned invalid cluster and resumes failed run`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val statistics = mockk<ClusterStatisticsService>(relaxed = true)
        val audits = mockk<BusinessAuditService>(relaxed = true)
        every { repository.lockRun(7L) } returns runRow(7L, UUID.randomUUID().toString()).copy(runStatus = "FAILED")
        every { repository.invalidOwnedClusters(7L) } returns listOf(68183L)
        every { repository.countUnownedActiveMains(listOf(68183L), 7L) } returns 0L
        every { repository.deactivateOwnedMains(listOf(68183L), 7L) } returns 1
        every { repository.run(7L) } returns runRow(7L, UUID.randomUUID().toString())
        val service = service(repository, statistics = statistics, audits = audits)

        val result = service.repairNameQualityFailure(7L, "重建占位混合簇成员", 9L)

        assertEquals("RUNNING", result.runStatus)
        verify { repository.archiveWithoutActiveMain(listOf(68183L), "重分类名称质量门禁修复后重建成员", 9L, true) }
        verify(exactly = 1) { repository.resumeFailed(7L) }
        verify(exactly = 1) { statistics.recomputeAll() }
        verify { audits.append(match { it.operation == "CLUSTER_RECLASS_NAME_REPAIR_STARTED" }) }
    }

    @Test
    fun `explicit member repair deactivates only owned selected mains and resumes completed run`() {
        val repository = mockk<ClusterReclassificationRepository>(relaxed = true)
        val statistics = mockk<ClusterStatisticsService>(relaxed = true)
        val audits = mockk<BusinessAuditService>(relaxed = true)
        val completed = runRow(7L, UUID.randomUUID().toString()).copy(runStatus = "COMPLETED")
        every { repository.lockRun(7L) } returns completed
        every { repository.ownedActiveMains(listOf(11L, 12L), 7L) } returns listOf(
            ClusterLinkPo(id = 101, feedbackSourceId = 11, clusterId = 81),
            ClusterLinkPo(id = 102, feedbackSourceId = 12, clusterId = 81)
        )
        every { repository.deactivateLinks(listOf(101L, 102L)) } returns 2
        every { repository.run(7L) } returns completed.copy(runStatus = "RUNNING", completedAt = null)
        val service = service(repository, statistics = statistics, audits = audits)

        val result = service.repairMembers(7L, listOf(11L, 12L), "按当前对象边界重建成员", 9L)

        assertEquals("RUNNING", result.runStatus)
        verify { repository.archiveWithoutActiveMain(listOf(81L), "重分类显式成员修复后无活动MAIN", 9L, true) }
        verify(exactly = 1) { statistics.recompute(listOf(81L)) }
        verify(exactly = 1) { repository.resumeFailedOrCompleted(7L) }
        verify { audits.append(match { it.operation == "CLUSTER_RECLASS_MEMBER_REPAIR_STARTED" }) }
    }

    private fun service(
        repository: ClusterReclassificationRepository,
        engine: ClusteringEngine = mockk(relaxed = true),
        statistics: ClusterStatisticsService = mockk(relaxed = true),
        audits: BusinessAuditService = mockk(relaxed = true),
        aiEnabled: Boolean = false
    ) = ClusterReclassificationService(repository, engine, statistics, manager(), audits, aiEnabled)

    private fun manager(): PlatformTransactionManager = mockk<PlatformTransactionManager>().also {
        every { it.getTransaction(any<TransactionDefinition>()) } returns SimpleTransactionStatus()
        every { it.commit(any()) } returns Unit
        every { it.rollback(any()) } returns Unit
    }

    private fun runRow(id: Long, requestId: String) = ClusterReclassificationRunPo(
        id = id, publicId = ByteArray(16), requestId = requestId, runStatus = "RUNNING",
        reason = "重建问题簇边界", requestedBy = 9L
    )
}
