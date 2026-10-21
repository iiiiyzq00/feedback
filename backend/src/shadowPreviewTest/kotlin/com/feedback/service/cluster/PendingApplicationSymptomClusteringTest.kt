package com.feedback.service.cluster

import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatch
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import com.feedback.model.applicationidentity.ApplicationIdentitySafeText
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingApplicationSymptomClusteringTest {
    private val extractor = AtomicProblemExtractorV4()
    private val classifier = ClusterShadowClassifierV3(extractor = extractor)

    @Test
    fun `jira quality and redblack unique canonical body mention resolves authoritative application`() {
        val course = identity(1, "知识课程")
        val tutor = identity(2, "AI学伴")
        val matcher = CanonicalApplicationTextMatcher()

        listOf("JIRA", "QUALITY", "REDBLACK").forEach { channel ->
            val match = matcher.match(
                ApplicationIdentitySafeText("$channel 问题", "知识课程内容打不开"),
                listOf(course, tutor)
            )
            assertEquals(course, (match as CanonicalApplicationTextMatch.Resolved).identity)
        }
    }

    @Test
    fun `unresolved records are clustered by symptom across channels without inventing application`() {
        val inputs = listOf(
            pending(1, "JIRA", "题目提交失败"),
            pending(2, "QUALITY", "题目提交失败"),
            pending(3, "REDBLACK", "题目提交失败")
        )

        val result = classifier.classify(inputs)

        assertEquals(1, result.clusterCount)
        assertTrue(result.assignments.all { it.status == ProblemClassificationStatus.ACTIONABLE })
        assertEquals(1, result.assignments.mapNotNull { it.clusterKey }.toSet().size)
        inputs.forEach { input ->
            val profile = extractor.extract(input)
            assertNull(profile.applicationKey)
            assertEquals("题目作答与提交待确认", profile.applicationName)
            assertEquals(EngineeringCapabilityDomain.QUESTION_SUBMISSION, profile.capabilityDomain)
            assertEquals(EngineeringFaultFamily.UNAVAILABLE, profile.faultFamily)
            assertEquals(ApplicationClusteringScope.PENDING_UNRESOLVED, profile.applicationScope)
            assertEquals("提交题目时失败", profile.canonicalSymptom)
        }
    }

    @Test
    fun `same symptom stays separate for each resolved application and pending identity`() {
        val result = classifier.classify(listOf(
            resolved(1, "app-a", "应用甲", "题目提交失败"),
            resolved(2, "app-b", "应用乙", "题目提交失败"),
            pending(3, "JIRA", "题目提交失败")
        ))

        assertEquals(3, result.clusterCount)
        assertEquals(3, result.assignments.mapNotNull { it.clusterKey }.toSet().size)
    }

    @Test
    fun `ambiguous and zero-match application evidence use separate pending partitions`() {
        val result = classifier.classify(listOf(
            pending(1, "JIRA", "题目提交失败"),
            pending(2, "REDBLACK", "题目提交失败", ambiguous = true)
        ))

        assertEquals(2, result.clusterCount)
        assertEquals(2, result.assignments.mapNotNull { it.clusterKey }.toSet().size)
        assertEquals(
            setOf(ApplicationClusteringScope.PENDING_UNRESOLVED, ApplicationClusteringScope.PENDING_AMBIGUOUS),
            listOf(
                extractor.extract(pending(1, "JIRA", "题目提交失败")),
                extractor.extract(pending(2, "REDBLACK", "题目提交失败", ambiguous = true))
            ).map { it.applicationScope }.toSet()
        )
    }

    @Test
    fun `semantic recall cannot cross pending application partitions`() {
        val unresolved = extractor.extract(pending(1, "JIRA", "题目提交失败"))
        val ambiguous = extractor.extract(pending(2, "REDBLACK", "题目提交失败", ambiguous = true))
        val recall = SemanticCandidateRecallV3(
            embeddings = SemanticEmbeddingPort { listOf(1f, 0f, 0f) }
        )

        assertTrue(recall.recall(unresolved, listOf(ambiguous)).isEmpty())
        assertEquals(java.math.BigDecimal("0.000000"), recall.similarity(unresolved, ambiguous))
    }

    @Test
    fun `review-needed body never becomes a pending symptom cluster`() {
        val result = classifier.classify(listOf(
            pending(1, "REDBLACK", "知识课程题目提交失败").copy(
                contentDisposition = ShadowContentDisposition.MASKING_REVIEW_REQUIRED
            )
        ))

        assertEquals(0, result.clusterCount)
        assertEquals(ProblemClassificationStatus.CONTENT_REVIEW_NEEDED, result.assignments.single().status)
        assertNull(result.assignments.single().clusterKey)
    }

    @Test
    fun `quality hardware fields remain pending and never become software application`() {
        val input = pending(1, "QUALITY", "设备无法开机").copy(
            functionModule = "主板",
            issueCategory = "硬件故障"
        )

        val profile = extractor.extract(input)

        assertNull(profile.applicationKey)
        assertEquals(ApplicationClusteringScope.PENDING_UNRESOLVED, profile.applicationScope)
        assertTrue(profile.applicationPartitionKey().startsWith("pending:"))
    }

    @Test
    fun `resolved v4 fingerprint is versioned away from verified v3 generation`() {
        val input = resolved(1, "app-a", "应用甲", "题目提交失败")
        assertTrue(AtomicProblemExtractorV3().extract(input).identityKey != extractor.extract(input).identityKey)
    }

    private fun pending(id: Long, channel: String, description: String, ambiguous: Boolean = false) =
        ClusterClassificationInput(
            sourceId = id,
            applicationKey = null,
            applicationName = null,
            title = null,
            description = description,
            applicationAmbiguous = ambiguous,
            sourceChannel = channel
        )

    private fun resolved(id: Long, key: String, name: String, description: String) =
        ClusterClassificationInput(id, key, name, null, description)

    private fun identity(id: Long, name: String) = ApplicationIdentitySnapshot(
        id,
        "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
        name,
        1
    )
}
