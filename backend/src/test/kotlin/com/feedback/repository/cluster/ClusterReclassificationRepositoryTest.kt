package com.feedback.repository.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ClusterReclassificationRepositoryTest {
    private val runs = mockk<ClusterReclassificationRunMapper>(relaxed = true)
    private val snapshots = mockk<ClusterReclassificationSnapshotMapper>(relaxed = true)
    private val contents = mockk<ClusterReclassificationContentMapper>(relaxed = true)
    private val links = mockk<ClusterReclassificationLinkMapper>(relaxed = true)
    private val clusters = mockk<ClusterReclassificationClusterMapper>(relaxed = true)
    private val clusterTable = mockk<ClusterTableMapper>(relaxed = true)
    private val repository = ClusterReclassificationRepository(runs, snapshots, contents, links, clusters, clusterTable)

    @Test
    fun `start persists lifecycle snapshot before superseding links and archiving old clusters`() {
        every { links.findActiveSnapshotClusterIds() } returns listOf(8L)
        every { clusterTable.findByIds(listOf(8L)) } returns listOf(ClusterPo(
            id = 8L, clusterStatus = "MITIGATING", statusReason = "处理中", lifecycleVersion = 3
        ))
        every { snapshots.insertAll(any()) } returns 1
        every { clusters.archiveAll(any()) } returns 1

        repository.snapshotAndIsolate(7L, 9L)

        verifyOrder {
            snapshots.insertAll(match { it.single().originalStatus == "MITIGATING" && it.single().originalLifecycleVersion == 3L })
            links.supersedeCandidates(7L)
            links.supersedeMains(7L)
            clusters.archiveAll(match { rows ->
                rows.single().id == 8L && !rows.single().onlyOperational && rows.single().updateActor
            })
        }
    }

    @Test
    fun `coverage intersects safe content and active main facts without joins`() {
        val time = LocalDateTime.of(2026, 9, 2, 12, 0)
        every { contents.findSafeAfter(null, 0L, 200) } returns listOf(
            ClusterReclassificationContentCursorPo(11L, time),
            ClusterReclassificationContentCursorPo(12L, time.plusSeconds(1))
        )
        every { links.findActiveMainAfter(0L, 200) } returns listOf(
            ClusterLinkPo(id = 1, feedbackSourceId = 11, reclassificationRunId = 7),
            ClusterLinkPo(id = 2, feedbackSourceId = 12, reclassificationRunId = 7),
            ClusterLinkPo(id = 3, feedbackSourceId = 99, reclassificationRunId = null)
        )

        assertEquals(ClusterReclassificationCoverage(2, 2, 2, 2, 0), repository.coverage(7L))
    }

    @Test
    fun `rollback clears run outputs before restoring every superseded main`() {
        every { links.countSupersededMains(7L) } returns 2L
        every { links.restoreSupersededMains(7L) } returns 2

        repository.rollbackLinks(7L)

        verifyOrder {
            links.countSupersededMains(7L)
            links.deactivateAllActiveMains()
            links.restoreSupersededMains(7L)
            links.restoreSupersededCandidates(7L)
        }
    }

    @Test
    fun `snapshot restore rejects partial cluster lifecycle update`() {
        every { snapshots.findByRunId(7L) } returns listOf(
            ClusterReclassificationSnapshotPo(7L, 8L, "ACTIVE", null, null, null, 1L),
            ClusterReclassificationSnapshotPo(7L, 9L, "MITIGATING", "处理中", 3L, null, 2L),
        )
        every { clusters.restoreAll(any()) } returns 1

        val error = assertThrows(ServiceException::class.java) { repository.restoreSnapshots(7L) }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("CLUSTER_RECLASSIFICATION_SNAPSHOT_RESTORE_MISMATCH", error.reasonCode)
    }

    @Test
    fun `failure transition rejects lost lease or missing run`() {
        every { runs.fail(7L, "worker-a", "failed") } returns 0

        val error = assertThrows(ServiceException::class.java) { repository.fail(7L, "worker-a", "failed") }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("CLUSTER_RECLASSIFICATION_FAILURE_STATE_CONFLICT", error.reasonCode)
    }
}
