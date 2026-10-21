package com.feedback.service.query.analysis

import com.feedback.application.port.problem.analysis.ClusterShadowPreviewDataset
import com.feedback.application.port.problem.analysis.ClusterShadowPreviewReadPort
import com.feedback.application.port.problem.analysis.ClusterShadowSafeProblemEvidence
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.auth.AuthProvider
import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class ClusterShadowPreviewApplicationServiceTest {
    private val reads = mockk<ClusterShadowPreviewReadPort>()
    private val principal = mockk<CurrentWebPrincipalProvider>()
    private val phenomena = mockk<ClusterShadowPreviewPhenomenonProjector>()

    @Test
    fun `projects only anonymous safe metadata and supports governed filters`() {
        every { principal.require() } returns admin()
        every { reads.readVerified(ClusterShadowPreviewApplicationService.ALLOWED_GENERATION) } returns dataset()
        every { phenomena.project(any(), any(), any(), any()) } returns
            ClusterShadowReadableProblem("AI学伴", "内容加载失败")
        val service = ClusterShadowPreviewApplicationService(reads, principal, phenomena)

        val page = service.list(ClusterShadowPreviewQuery(largeOnly = true, provisionalOnly = true, crossChannelOnly = true))

        assertEquals(1, page.total)
        assertEquals("SC-0001", page.items.single().previewId)
        assertEquals("AI学伴", page.items.single().application)
        assertEquals("内容加载失败", page.items.single().phenomenon)
        assertEquals("AI学伴·内容加载失败（独立问题边界 1）", page.items.single().problem)
        assertEquals(26_141L, page.analysisSections.sumOf { it.recordCount })
        assertEquals(
            listOf(10_558L, 0L, 9_481L, 6_102L),
            page.analysisSections.map { it.recordCount }
        )
        assertTrue(page.analysisSections.single { it.key == "CONFIRMED_APPLICATION" }.detailAvailable)
        assertTrue(page.analysisSections.single { it.key == "PENDING_APPLICATION" }.detailAvailable)
        assertEquals(0, page.items.single().boundaries.conflictCount)
        assertTrue(page.readOnly)
        val detail = service.detail("SC-0001", 1, 2)
        assertEquals(listOf("SC-0001-M0001", "SC-0001-M0002"), detail.members.map { it.anonymousId })
        assertFalse(detail.bodyProjectionAvailable)
        assertTrue(detail.members.all { it.application == "AI学伴" })
    }

    @Test
    fun `fails closed for non admin before reading candidates`() {
        every { principal.require() } returns admin(roles = listOf("OPERATOR"))
        val service = ClusterShadowPreviewApplicationService(reads, principal, phenomena)

        val error = assertThrows(ClientException::class.java) { service.list(ClusterShadowPreviewQuery()) }

        assertEquals("CLUSTER_SHADOW_PREVIEW_ADMIN_REQUIRED", error.reasonCode)
    }

    @Test
    fun `filters confirmed and pending lanes without weakening the application boundary`() {
        every { principal.require() } returns admin()
        every { reads.readVerified(ClusterShadowPreviewApplicationService.ALLOWED_GENERATION) } returns dataset()
        every { phenomena.project(any(), any(), any(), any()) } returns
            ClusterShadowReadableProblem("AI学伴", "内容加载失败")
        val service = ClusterShadowPreviewApplicationService(reads, principal, phenomena)

        val confirmed = service.list(ClusterShadowPreviewQuery(analysisSection = "CONFIRMED_APPLICATION"))
        val pending = service.list(ClusterShadowPreviewQuery(analysisSection = "PENDING_APPLICATION"))

        assertEquals(ClusterShadowPreviewApplicationService.CLUSTER_COUNT, confirmed.total)
        assertEquals(0L, pending.total)
        val error = assertThrows(ClientException::class.java) {
            service.list(ClusterShadowPreviewQuery(analysisSection = "ENGINEERING_CLASSIFICATION"))
        }
        assertEquals("CLUSTER_SHADOW_PREVIEW_SECTION_INVALID", error.reasonCode)
    }

    @Test
    fun `fails closed when a five field boundary conflicts`() {
        every { principal.require() } returns admin()
        val unsafe = dataset().let { value ->
            val members = value.members.toMutableList()
            members[1] = members[1].copy(failureModeHash = "different")
            value.copy(members = members)
        }
        every { reads.readVerified(any()) } returns unsafe
        val service = ClusterShadowPreviewApplicationService(reads, principal, phenomena)

        val error = assertThrows(ServiceException::class.java) { service.list(ClusterShadowPreviewQuery()) }

        assertEquals("CLUSTER_SHADOW_PREVIEW_HARD_BOUNDARY_CONFLICT", error.reasonCode)
    }

    private fun dataset(): ClusterShadowPreviewDataset {
        val generation = ClusterShadowGenerationPo(
            id = 11,
            generationKey = ClusterShadowPreviewApplicationService.ALLOWED_GENERATION,
            generationStatus = "VERIFIED",
            algorithmVersion = ClusterShadowPreviewApplicationService.ALGORITHM_VERSION,
            modelVersion = "BAAI/bge-m3-onnx-dense-v1",
            processedCount = ClusterShadowPreviewApplicationService.INPUT_COUNT,
            actionableCount = ClusterShadowPreviewApplicationService.ACTIONABLE_COUNT,
            terminalCount = ClusterShadowPreviewApplicationService.TERMINAL_COUNT,
            clusterCount = ClusterShadowPreviewApplicationService.CLUSTER_COUNT,
            provisionalCount = ClusterShadowPreviewApplicationService.PROVISIONAL_COUNT,
            verifiedAt = LocalDateTime.of(2026, 9, 10, 0, 24)
        )
        val clusters = (1..ClusterShadowPreviewApplicationService.CLUSTER_COUNT.toInt()).map { index ->
            val firstCount = ClusterShadowPreviewApplicationService.ACTIONABLE_COUNT -
                ClusterShadowPreviewApplicationService.CLUSTER_COUNT + 1
            ClusterShadowCandidateClusterPo(
                11, hash(index), hash(index + 2_000), if (index == 1) firstCount else 1,
                if (index == 1) ClusterShadowPreviewApplicationService.PROVISIONAL_COUNT else 0, 0
            )
        }
        var sourceId = 1L
        val members = clusters.flatMapIndexed { clusterIndex, cluster ->
            (1..cluster.memberCount.toInt()).map { memberIndex ->
                ClusterShadowCandidateMemberPo(
                    11, sourceId++, "ACTIONABLE", cluster.clusterKeyHash,
                if (clusterIndex == 0 && memberIndex <= ClusterShadowPreviewApplicationService.PROVISIONAL_COUNT)
                    "PROVISIONAL_LINK_EXISTING" else "LINK_EXISTING",
                    BigDecimal("0.820000"), false, "app", "failure", null, null, null
                )
            }
        }
        val evidence = members.associate { member ->
            member.feedbackSourceId to ClusterShadowSafeProblemEvidence(
                member.feedbackSourceId,
                if (member.feedbackSourceId == 2L) "JIRA" else "STATION_FEEDBACK",
                "app-key", "AI学伴", "内容加载失败", "内容加载失败", null, null
            )
        }
        return ClusterShadowPreviewDataset(generation, clusters, members, evidence, terminalCounts())
    }

    private fun admin(roles: List<String> = listOf("ADMIN")) = WebPrincipal(
        9, "admin", "-", "Admin", roles, emptyList(), authProvider = AuthProvider.LOCAL.code
    )

    private fun hash(value: Int) = value.toString(16).padStart(64, '0')

    private fun terminalCounts() = mapOf(
        "INSUFFICIENT_INFORMATION" to 118L, "USER_INTENT_INCOMPLETE" to 1_195L,
        "NOISE" to 5_081L, "NON_FAULT_INPUT" to 7_008L,
        "LEGACY_INFORMATION_LOSS" to 903L, "NOVEL_SYMPTOM" to 421L,
        "MULTI_PROBLEM_AMBIGUOUS" to 857L
    )
}
