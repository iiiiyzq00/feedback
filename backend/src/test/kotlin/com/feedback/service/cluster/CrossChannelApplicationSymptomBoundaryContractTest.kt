package com.feedback.service.cluster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossChannelApplicationSymptomBoundaryContractTest {
    private val recall = ClusterCandidateRecallV2()

    @Test
    fun `正文唯一命中形成已批准 application 输入后仍按症状事实拆簇`() {
        val result = ClusterShadowClassifierV3().classify(
            listOf(
                input(1, "knowledge-course", "知识课程", "播放视频时卡住", "JIRA"),
                input(2, "knowledge-course", "知识课程", "播放视频没有声音", "REDBLACK")
            )
        )

        assertEquals(2, result.clusterCount)
        assertNotEquals(result.assignments[0].clusterKey, result.assignments[1].clusterKey)
        assertTrue(result.assignments.all { it.status == ProblemClassificationStatus.ACTIONABLE })
    }

    @Test
    fun `不同 application 即使症状完全相同也绝不合并`() {
        val result = ClusterShadowClassifierV3().classify(
            listOf(
                input(11, "knowledge-course", "知识课程", "播放视频时卡顿", "JIRA"),
                input(12, "picture-book", "绘本伴读", "播放视频时卡顿", "REDBLACK")
            )
        )

        assertEquals(2, result.clusterCount)
        assertNotEquals(result.assignments[0].clusterKey, result.assignments[1].clusterKey)
    }

    @Test
    fun `候选召回对五项硬边界的已知冲突全部失败关闭`() {
        val base = profile(21)
        val conflicts = listOf(
            profile(22, application = "other-app"),
            profile(23, target = "课程"),
            profile(24, action = "OPEN"),
            profile(25, failureMode = "NO_AUDIO"),
            profile(26, errorSignature = "E200")
        )

        conflicts.forEach { query ->
            assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(query, base))
            assertTrue(recall.recall(query, listOf(base)).isEmpty())
        }
    }

    private fun input(
        sourceId: Long,
        applicationKey: String,
        applicationName: String,
        description: String,
        channel: String
    ) = ClusterClassificationInput(
        sourceId = sourceId,
        applicationKey = applicationKey,
        applicationName = applicationName,
        title = null,
        description = description,
        sourceChannel = channel,
        applicationEvidence = ApplicationIdentityEvidence.APPROVED_IDENTITY
    )

    private fun profile(
        sourceId: Long,
        application: String = "knowledge-course",
        target: String = "视频",
        action: String = "PLAY",
        failureMode: String = "FREEZE",
        errorSignature: String = "E100"
    ) = AtomicProblemProfile(
        sourceId = sourceId,
        applicationKey = application,
        applicationName = application,
        status = ProblemClassificationStatus.ACTIONABLE,
        primaryClause = "播放视频时卡顿 $errorSignature",
        target = target,
        action = action,
        failureMode = failureMode,
        errorSignature = errorSignature,
        canonicalSymptom = "播放视频时卡顿 $errorSignature",
        identityKey = "identity-$sourceId",
        reason = "contract fixture",
        evidenceSource = ProblemEvidenceSource.DIRECT_TEXT
    )
}
