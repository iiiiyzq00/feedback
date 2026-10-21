package com.feedback.service.cluster

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.feedback.service.sync.HybridCandidate
import java.nio.file.Files
import java.nio.file.Path

class ClusteringExactRecallContractTest {
    @Test
    fun `exact canonical recall precedes bounded recent fallback`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/feedback/service/cluster/ClusteringEngine.kt"))
        val exact = source.indexOf("repository.candidatesByExactProfile")
        val recent = source.indexOf("repository.candidatesByProduct", exact)

        assertTrue(exact >= 0)
        assertTrue(recent > exact)
        assertTrue(source.contains("repository.clusters(recallsByCluster.keys)"))
        assertTrue(source.contains("exactCandidates + signatureCandidates"))
        assertTrue(source.contains("repository.candidatesByFingerprint(profile.problemFingerprint)"))
        assertTrue(source.contains("repository.candidatesBySignature(profile.productScope, it)"))
        assertTrue(source.contains("cluster.id in strongRecallIds ||"))
        assertTrue(source.contains("eligibleCandidates.filter { it.id in strongRecallIds }"))
        assertTrue(source.contains("repository.rejectedClusterIds(sourceId)"))

        val repository = Files.readString(
            Path.of("src/main/kotlin/com/feedback/repository/cluster/ClusterRepositories.kt")
        )
        assertTrue(repository.contains("clusters.findByExactProfile("))
        assertTrue(repository.contains("links.findClusterIdsByProblemFingerprint("))
        assertTrue(repository.contains("links.findRejectedClusterIds(sourceId)"))

        val clusterMapper = Files.readString(
            Path.of("src/main/resources/mybatis/mapper/cluster/ClusterTableMapper.xml")
        )
        val linkMapper = Files.readString(
            Path.of("src/main/resources/mybatis/mapper/cluster/ClusterLinkTableMapper.xml")
        )
        assertTrue(clusterMapper.contains("id=\"findByExactProfile\""))
        assertTrue(linkMapper.contains("id=\"findClusterIdsByProblemFingerprint\""))
        assertTrue(linkMapper.contains("id=\"findRejectedClusterIds\""))
    }

    @Test
    fun `source level recall collapses to the strongest evidence per cluster`() {
        val recalls = listOf(
            HybridCandidate(11, 0.020, 1, null, null),
            HybridCandidate(12, 0.030, 2, 1, 0.95f),
            HybridCandidate(13, 0.025, 3, 2, 0.92f)
        )

        val result = strongestRecallPerCluster(recalls, mapOf(11L to 7L, 12L to 7L, 13L to 8L))

        assertEquals(setOf(7L, 8L), result.keys)
        assertEquals(12L, result.getValue(7L).sourceId)
        assertEquals(13L, result.getValue(8L).sourceId)
    }

    @Test
    fun `new cluster display name uses the formatted canonical phenomenon`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/feedback/service/cluster/ClusteringEngine.kt"))

        assertTrue(source.contains("repository.insertOrReuse(ClusterInsertPo("))
        assertTrue(source.contains("clusterName = effectiveCanonical"))
        assertTrue(source.contains("clusterNameKey = nameKey"))
        assertTrue(source.contains("canonicalSymptom = effectiveCanonical"))
    }

    @Test
    fun `member fingerprint is deterministic and sensitive to the atomic problem`() {
        val first = clusterProblemFingerprint(
            "LEARNING_MACHINE", "VIDEO", "VIDEO_MEDIA", "PLAY", setOf("PLAYBACK"),
            emptySet(), "ACTIONABLE", null, "播放课程视频时黑屏", ClusterProblemOntology.VERSION
        )
        val same = clusterProblemFingerprint(
            "LEARNING_MACHINE", "VIDEO", "VIDEO_MEDIA", "PLAY", setOf("PLAYBACK"),
            emptySet(), "ACTIONABLE", null, "播放课程视频时黑屏", ClusterProblemOntology.VERSION
        )
        val different = clusterProblemFingerprint(
            "LEARNING_MACHINE", "VIDEO", "VIDEO_MEDIA", "DOWNLOAD", setOf("DOWNLOAD"),
            emptySet(), "ACTIONABLE", null, "课程视频下载失败", ClusterProblemOntology.VERSION
        )
        assertEquals(64, first.length)
        assertEquals(first, same)
        assertTrue(first != different)
    }
}
