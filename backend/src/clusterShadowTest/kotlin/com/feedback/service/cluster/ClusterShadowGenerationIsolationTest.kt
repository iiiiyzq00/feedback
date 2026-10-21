package com.feedback.service.cluster

import com.feedback.common.exception.ServiceException
import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.service.sync.EmbeddingProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ClusterShadowGenerationIsolationTest {
    private val repository = mockk<ClusterShadowGenerationRepository>(relaxed = true)
    private val input = HistoricalClusterShadowInputPort { after, _ ->
        if (after > 0) HistoricalClusterShadowBatch(emptyList(), after, false)
        else HistoricalClusterShadowBatch(records(), 4, false)
    }
    private val embeddings = object : EmbeddingProvider {
        override val modelVersion = "BAAI/bge-m3-onnx-dense-v1"
        override val dimension = 1024
        override fun embed(text: String): List<Float> = List(dimension) { index ->
            if (index == Math.floorMod(text.hashCode(), dimension)) 1f else 0f
        }
    }

    @Test
    fun `BUILD 正反序一致并且只持久化不可逆候选摘要`() {
        val fixture = buildFixture()

        assertEquals("BUILDING", fixture.result.generationStatus)
        assertEquals(4, fixture.result.processedCount)
        assertEquals(fixture.generation.assignmentDigest, fixture.generation.reverseAssignmentDigest)
        assertNotNull(fixture.generation.inputDigest)
        assertTrue(fixture.members.all { it.applicationHash == null || it.applicationHash!!.matches(HASH) })
        assertTrue(fixture.members.all { it.clusterKeyHash == null || it.clusterKeyHash!!.matches(HASH) })
        assertTrue(fixture.clusters.all { it.clusterNameHash.matches(HASH) })
        verify(exactly = 1) { repository.persistBuild(any(), any(), any()) }
    }

    @Test
    fun `VERIFY 通过覆盖摘要和五字段门禁后只转 VERIFIED`() {
        val fixture = buildFixture()
        every { repository.findByKey("shadow-full-1") } returns fixture.generation
        every { repository.members(fixture.generation.id) } returns fixture.members
        every { repository.clusters(fixture.generation.id) } returns fixture.clusters
        every { repository.markVerified("shadow-full-1", 9, any()) } answers {
            fixture.generation.copy(generationStatus = "VERIFIED", verifiedBy = 9, verificationDigest = thirdArg())
        }

        val verified = fixture.service.verify(ClusterShadowGenerationCommand("shadow-full-1", 9))

        assertEquals("VERIFIED", verified.generationStatus)
        assertTrue(verified.verificationDigest!!.matches(HASH))
        verify(exactly = 1) { repository.markVerified("shadow-full-1", 9, any()) }
    }

    @Test
    fun `VERIFY 对候选簇内 application 哈希冲突失败关闭`() {
        val fixture = buildFixture()
        val repeatedCluster = fixture.members.groupBy { it.clusterKeyHash }.values.first { rows ->
            rows.size >= 2 && rows.all { !it.manualLocked }
        }
        val conflicting = fixture.members.map { row ->
            if (row.feedbackSourceId == repeatedCluster.last().feedbackSourceId) row.copy(applicationHash = "f".repeat(64))
            else row
        }
        every { repository.findByKey("shadow-full-1") } returns fixture.generation
        every { repository.members(fixture.generation.id) } returns conflicting
        every { repository.clusters(fixture.generation.id) } returns fixture.clusters

        val error = assertThrows(ServiceException::class.java) {
            fixture.service.verify(ClusterShadowGenerationCommand("shadow-full-1", 9))
        }

        assertEquals("CLUSTER_SHADOW_GENERATION_HARD_BOUNDARY_CONFLICT", error.reasonCode)
        // 落库的失败码取异常自带的原因码，而不是兜底常量：否则运维看到的失败原因
        // 会和抛出的错误码不一致，排障时对不上。
        verify(exactly = 1) {
            repository.markFailed("shadow-full-1", "CLUSTER_SHADOW_GENERATION_HARD_BOUNDARY_CONFLICT", any())
        }
        verify(exactly = 0) { repository.markVerified(any(), any(), any()) }
    }

    @Test
    fun `BUILD 遇到 BGE 语义降级时不得持久化或伪装成功`() {
        val brokenEmbedding = object : EmbeddingProvider {
            override val modelVersion = "BAAI/bge-m3-onnx-dense-v1"
            override val dimension = 1024
            override fun embed(text: String): List<Float> = error("model unavailable")
        }
        val semanticInput = HistoricalClusterShadowInputPort { _, _ -> HistoricalClusterShadowBatch(
            listOf(
                row(11, "app-a", "应用甲", "视频无法播放"),
                row(12, "app-a", "应用甲", "无法播放")
            ), 12, false
        ) }
        val row = slot<ClusterShadowGenerationPo>()
        every { repository.createBuilding(capture(row)) } answers { row.captured.apply { id = 42 } }
        val service = ClusterShadowGenerationService(repository, semanticInput, brokenEmbedding)

        val error = assertThrows(ServiceException::class.java) {
            service.build(ClusterShadowGenerationCommand("shadow-full-degraded", 9))
        }

        assertEquals("CLUSTER_SHADOW_GENERATION_BGE_M3_RUNTIME_FAILED", error.reasonCode)
        verify(exactly = 0) { repository.createBuilding(any()) }
        verify(exactly = 0) { repository.persistBuild(any(), any(), any()) }
        verify(exactly = 0) { repository.markFailed(any(), any(), any()) }
    }

    @Test
    fun `迁移与运行入口没有 ACTIVE 或现有 Cluster Link 写路径`() {
        val migration = Files.readString(Path.of(
            "src/main/resources/db/migration/V241__create_isolated_cluster_shadow_generations.sql"
        ))
        val normalized = migration.lowercase()
        assertTrue("generation_status in ('building','verified','failed')" in normalized)
        assertTrue("t_cluster_shadow_candidate_member" in normalized)
        assertTrue("t_cluster_shadow_candidate_cluster" in normalized)
        assertTrue(!Regex("""(?:insert\s+into|update|delete\s+from|alter\s+table)\s+t_cluster(?:_link)?\b""")
            .containsMatchIn(normalized))
        assertTrue("'active'" !in normalized)
    }

    @Test
    fun `隔离 generation 三个 MyBatis 映射可独立解析`() {
        val configuration = Configuration()
        listOf(
            "mybatis/mapper/cluster/ClusterShadowGenerationMapper.xml",
            "mybatis/mapper/cluster/ClusterShadowCandidateClusterMapper.xml",
            "mybatis/mapper/cluster/ClusterShadowCandidateMemberMapper.xml"
        ).forEach { resource ->
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.mappedStatementNames.any { it.endsWith(".markVerified") })
        assertTrue(configuration.mappedStatementNames.any { it.endsWith(".insertAll") })
    }

    @Test
    fun `four channel shadow SQL excludes source identifiers and nulls restricted text`() {
        val sourceXml = Files.readString(Path.of(
            "src/main/resources/mybatis/mapper/cluster/ClusterSourceTableMapper.xml"
        ))
        val contentXml = Files.readString(Path.of(
            "src/main/resources/mybatis/mapper/cluster/ClusterContentTableMapper.xml"
        ))
        val sourceStatement = Regex(
            "(?s)<select id=\"findShadowSafeByIds\".*?</select>"
        ).find(sourceXml)?.value ?: error("CLUSTER_SHADOW_SAFE_SOURCE_SQL_MISSING")
        val contentStatement = Regex(
            "(?s)<select id=\"findShadowSafeByIds\".*?</select>"
        ).find(contentXml)?.value ?: error("CLUSTER_SHADOW_SAFE_CONTENT_SQL_MISSING")

        assertTrue("source_record_id" !in sourceStatement)
        assertTrue("package_name" !in sourceStatement)
        assertTrue("SELECT id,source_channel" in sourceStatement)
        assertTrue("CASE WHEN masking_status='PASS' AND is_deleted=0 THEN title ELSE NULL END" in contentStatement)
        assertTrue("CASE WHEN masking_status='PASS' AND is_deleted=0 THEN description ELSE NULL END" in contentStatement)
    }

    private fun buildFixture(): Fixture {
        val generationSlot = slot<ClusterShadowGenerationPo>()
        val clusterSlot = slot<List<ClusterShadowCandidateClusterPo>>()
        val memberSlot = slot<List<ClusterShadowCandidateMemberPo>>()
        every { repository.createBuilding(capture(generationSlot)) } answers { generationSlot.captured.apply { id = 41 } }
        every { repository.persistBuild(capture(generationSlot), capture(clusterSlot), capture(memberSlot)) } returns Unit
        val service = ClusterShadowGenerationService(repository, input, embeddings)
        val result = service.build(ClusterShadowGenerationCommand("shadow-full-1", 9))
        return Fixture(service, result, generationSlot.captured, clusterSlot.captured, memberSlot.captured)
    }

    private fun records() = listOf(
        row(1, "app-a", "应用甲", "视频无法播放"),
        row(2, "app-a", "应用甲", "视频无法播放"),
        row(3, "app-b", "应用乙", "视频无法播放"),
        row(4, null, null, "视频无法播放")
    )

    private fun row(id: Long, app: String?, appName: String?, text: String) = HistoricalClusterShadowInput(
        ClusterClassificationInput(id, app, appName, text, text), null
    )

    private data class Fixture(
        val service: ClusterShadowGenerationService,
        val result: ClusterShadowGenerationResult,
        val generation: ClusterShadowGenerationPo,
        val clusters: List<ClusterShadowCandidateClusterPo>,
        val members: List<ClusterShadowCandidateMemberPo>
    )

    companion object { private val HASH = Regex("[0-9a-f]{64}") }
}
