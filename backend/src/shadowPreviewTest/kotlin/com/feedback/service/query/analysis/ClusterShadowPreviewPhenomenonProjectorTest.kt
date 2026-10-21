package com.feedback.service.query.analysis

import com.feedback.application.port.problem.analysis.ClusterShadowSafeProblemEvidence
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.service.cluster.AtomicProblemExtractorV4
import com.feedback.service.cluster.ClusterClassificationInput
import com.feedback.service.cluster.DeterministicClusterNamePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.security.MessageDigest

class ClusterShadowPreviewPhenomenonProjectorTest {
    private val extractor = AtomicProblemExtractorV4()
    private val names = DeterministicClusterNamePolicy()
    private val projector = ClusterShadowPreviewPhenomenonProjector()

    @Test
    fun `rehydrates a verified readable symptom without exposing the body`() {
        val evidence = evidence(1, "app-a", "AI学伴")
        val profile = profile(evidence)
        val member = member(profile)
        val clusterKeyHash = hash("cluster-key", "symptom:${profile.identityKey}")
        val clusterNameHash = hash("cluster-name", names.name(profile))

        val phenomenon = projector.project(clusterKeyHash, clusterNameHash, listOf(member), mapOf(1L to evidence))

        assertEquals(
            ClusterShadowReadableProblem("AI学伴", profile.canonicalSymptom!!, "对象：视频；场景：播放"),
            phenomenon
        )
    }

    @Test
    fun `rehydrates unresolved application as a capability pending domain without inventing an app`() {
        val evidence = ClusterShadowSafeProblemEvidence(
            3, "JIRA", null, null, "提交题目时失败", "提交题目时失败", null, null
        )
        val profile = profile(evidence)
        val member = member(profile)

        val readable = projector.project(
            hash("cluster-key", "symptom:${profile.identityKey}"),
            hash("cluster-name", names.name(profile)),
            listOf(member),
            mapOf(3L to evidence)
        )

        assertEquals("题目作答与提交待确认", readable.application)
        assertEquals(profile.canonicalSymptom, readable.phenomenon)
    }

    @Test
    fun `the same symptom in different applications has different cluster identity`() {
        val left = profile(evidence(1, "app-a", "AI学伴"))
        val right = profile(evidence(2, "app-b", "绘本伴读"))

        assertEquals(left.canonicalSymptom, right.canonicalSymptom)
        assertNotEquals(left.identityKey, right.identityKey)
        assertNotEquals(
            hash("cluster-key", "symptom:${left.identityKey}"),
            hash("cluster-key", "symptom:${right.identityKey}")
        )
    }

    private fun evidence(id: Long, key: String, name: String) = ClusterShadowSafeProblemEvidence(
        id, "STATION_FEEDBACK", key, name, "视频无法播放", "视频无法播放", null, null
    )

    private fun profile(value: ClusterShadowSafeProblemEvidence) = extractor.extract(ClusterClassificationInput(
        value.sourceId, value.applicationKey, value.applicationName, value.title, value.description,
        value.functionModule, value.issueCategory, sourceChannel = value.channel
    ))

    private fun member(profile: com.feedback.service.cluster.AtomicProblemProfile) = ClusterShadowCandidateMemberPo(
        11, profile.sourceId, "ACTIONABLE", hash("cluster-key", "symptom:${profile.identityKey}"),
        "NEW_CLUSTER", BigDecimal.ZERO.setScale(6), false,
        hashNullable("application", profile.applicationKey), hashNullable("failure-mode", profile.failureMode),
        hashNullable("target", profile.target), hashNullable("action", profile.action),
        hashNullable("error-signature", profile.errorSignature)
    )

    private fun hashNullable(domain: String, value: String?) = value?.let { hash(domain, it) }
    private fun hash(domain: String, value: String) = MessageDigest.getInstance("SHA-256")
        .digest("cluster-shadow-generation-v1\u0000$domain\u0000$value".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
