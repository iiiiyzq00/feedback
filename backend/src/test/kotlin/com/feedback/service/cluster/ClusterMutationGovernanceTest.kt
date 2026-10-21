package com.feedback.service.cluster

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.controller.query.*
import com.feedback.model.cluster.ClusterLink
import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import com.feedback.repository.cluster.*
import com.feedback.model.auth.DataAccessScope
import com.feedback.manager.cluster.ClusterMutationAccessGuard
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.query.ClusterBatchReviewItem
import com.feedback.service.query.ClusterReviewCommand
import com.feedback.common.exception.ServiceException
import com.feedback.common.exception.ClientException
import com.feedback.service.query.ClusterReviewService
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.*
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class ClusterMutationGovernanceTest {
    @Test
    fun `batch 201 is rejected before any mapper backed dependency`() {
        val repository = mockk<ClusterMutationRepository>(relaxed = true)
        val ledger = mockk<ClusterCommandLedgerRepository>(relaxed = true)
        val service = ClusterReviewService(
            repository,
            mockk<ClusterProfileFactRepository>(relaxed = true),
            ledger,
            mockk<ClusterMutationAccessGuard>(relaxed = true),
            mockk<DataPermissionService>(relaxed = true),
            mockk<ClusterStatisticsService>(relaxed = true),
            mockk<BusinessAuditService>(relaxed = true),
            mockk<FeedbackListProjectionDirtyMarker>(relaxed = true),
            mockk<ProductScopeResolver>(relaxed = true),
            mockk<ProblemProfileExtractor>(relaxed = true),
            mockk<ChannelClusterContextResolver>(relaxed = true),
            jacksonObjectMapper()
        )
        val items = (1L..201L).map { id ->
            ClusterBatchReviewItem(id, "REJECT", null, 0, UUID.randomUUID().toString(), "批量拒绝原因")
        }

        val error = assertThrows(ClientException::class.java) { service.reviewBatch(items, 9L) }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("CLUSTER_REVIEW_BATCH_SIZE_INVALID", error.reasonCode)
        verify(exactly = 0) { ledger.claim(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.lockClusters(any()) }
    }

    @Test
    fun `repository rejects duplicate mapper rows and the 201 affected source sentinel`() {
        val clusterMapper = mockk<ClusterTableMapper>(relaxed = true)
        val linkMapper = mockk<ClusterLinkTableMapper>(relaxed = true)
        val repository = ClusterMutationRepository(
            clusterMapper, linkMapper, mockk(relaxed = true), mockk(relaxed = true)
        )
        every { clusterMapper.lockByIds(listOf(1L, 2L)) } returns listOf(
            ClusterPo(id = 1), ClusterPo(id = 1)
        )
        assertThrows(ServiceException::class.java) { repository.lockClusters(listOf(2L, 1L)) }

        every { linkMapper.lockActiveMainByClusterIds(listOf(7L), 201) } returns
            (1L..201L).map { id -> ClusterLinkPo(id = id, clusterId = 7, feedbackSourceId = id) }
        val boundary = assertThrows(ClientException::class.java) {
            repository.lockActiveMainsByClusters(listOf(7L), 200)
        }
        assertEquals("CLUSTER_AFFECTED_SOURCE_LIMIT_EXCEEDED", boundary.reasonCode)

        val skipRows = listOf(ClusterCandidateSkipPo(31L, 4L), ClusterCandidateSkipPo(32L, 4L))
        every { linkMapper.skipCandidates(skipRows, "自动关闭候选") } returns 1
        val skipConflict = assertThrows(ServiceException::class.java) {
            repository.skipCandidates(skipRows, "自动关闭候选")
        }
        assertEquals("CLUSTER_CANDIDATE_SKIP_CAS_CONFLICT", skipConflict.reasonCode)

        every { clusterMapper.reopenResolved(7L, 3L, "复发重开") } returns 1
        assertTrue(repository.reopenResolved(7L, 3L, "复发重开"))
        verify(exactly = 1) { clusterMapper.reopenResolved(7L, 3L, "复发重开") }
    }

    @Test
    fun `insert reuse result does not trust connector affected row mode`() {
        val clusterMapper = mockk<ClusterTableMapper>()
        val repository = ClusterMutationRepository(
            clusterMapper, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        val attemptedPublicId = ByteArray(16) { 1 }
        val existingPublicId = ByteArray(16) { 2 }
        val row = ClusterInsertPo(
            publicId = attemptedPublicId, clusterName = "视频无响应", clusterNameKey = "视频无响应",
            productScope = "学习机", functionModule = "VIDEO", issueCategory = "BUG",
            canonicalSymptom = "视频无响应", canonicalObject = "视频", canonicalTrigger = "PLAY",
            errorSignature = null
        )
        every { clusterMapper.insertOrReuse(row) } answers { row.id = 5L; 1 }
        every { clusterMapper.findByIds(listOf(5L)) } returns listOf(
            ClusterPo(id = 5L, publicId = existingPublicId, clusterName = "视频无响应",
                clusterNameKey = "视频无响应", clusterStatus = "ACTIVE")
        )

        val (_, created) = repository.insertOrReuse(row)

        assertFalse(created)
    }

    @Test
    fun `command ledger returns the original typed result and rejects abnormal completion rows`() {
        val mapper = mockk<ClusterCommandLedgerTableMapper>()
        val json = jacksonObjectMapper()
        val repository = ClusterCommandLedgerRepository(mapper, json)
        val requestId = UUID.randomUUID().toString()
        every { mapper.claim(any()) } answers {
            firstArg<ClusterCommandLedgerPo>().id = 31L
            0
        }
        every { mapper.findByFactKey(any()) } returns ClusterCommandLedgerPo(
            id = 31L,
            factKey = repository.fingerprint(requestId),
            operation = "CLUSTER_SPLIT",
            commandFingerprint = "fingerprint",
            targetId = 7L,
            requestId = requestId,
            factStatus = "COMPLETED",
            evaluationSnapshot = "{\"requestId\":\"$requestId\",\"clusterIds\":[11,12]}"
        )
        val replay = repository.claim(requestId, "CLUSTER_SPLIT", "fingerprint", 7L)
        assertTrue(replay.completed)
        assertEquals(listOf(11L, 12L), replay.result!!.get("clusterIds").map { it.asLong() })

        every { mapper.complete(31L, any()) } returns 0
        assertThrows(ServiceException::class.java) {
            repository.complete(31L, requestId, mapOf("clusterIds" to listOf(11L, 12L)))
        }
    }

    @Test
    fun `empty or partially out of scope target cluster fails closed`() {
        val permissions = mockk<DataPermissionService>()
        val facts = mockk<ClusterProfileFactRepository>()
        val repository = mockk<ClusterMutationRepository>()
        val guard = ClusterMutationAccessGuard(permissions, facts, repository)
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.visitActiveMainPages(listOf(1L), any()) } answers { }

        assertThrows(ClientException::class.java) { guard.requireClusters(listOf(1L)) }

        every { repository.visitActiveMainPages(listOf(2L), any()) } answers {
            secondArg<(List<ClusterLink>) -> Unit>().invoke(listOf(
                ClusterLink(id = 10, clusterId = 2, feedbackSourceId = 99, relationRole = "MAIN")
            ))
        }
        every { facts.accessFacts(listOf(99L)) } returns mapOf(
            99L to ClusterAccessFact(99L, "JIRA", "VIDEO", null, null)
        )
        assertThrows(ClientException::class.java) { guard.requireClusters(listOf(2L)) }
    }

    @Test
    fun `public mutation responses keep the existing json field names`() {
        val json = jacksonObjectMapper()
        assertEquals(setOf("confirmed"), json.readTree(json.writeValueAsString(ClusterConfirmedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("reviewed"), json.readTree(json.writeValueAsString(ClusterBatchReviewedResponse(1))).fieldNames().asSequence().toSet())
        assertEquals(setOf("clusterId"), json.readTree(json.writeValueAsString(ClusterCreatedResponse(9))).fieldNames().asSequence().toSet())
        assertEquals(setOf("rejected"), json.readTree(json.writeValueAsString(ClusterRejectedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("skipped"), json.readTree(json.writeValueAsString(ClusterSkippedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("unlinked"), json.readTree(json.writeValueAsString(ClusterUnlinkedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("changed"), json.readTree(json.writeValueAsString(ClusterStatusChangedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("merged"), json.readTree(json.writeValueAsString(ClusterMergedResponse(true))).fieldNames().asSequence().toSet())
        assertEquals(setOf("clusterIds"), json.readTree(json.writeValueAsString(ClusterSplitResponse(listOf(1, 2)))).fieldNames().asSequence().toSet())
        assertEquals(setOf("reopened"), json.readTree(json.writeValueAsString(ClusterReopenedResponse(true))).fieldNames().asSequence().toSet())
    }

    @Test
    fun `confirm fails closed when main changes between cluster and link locks`() {
        val fixture = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { fixture.repository.linksBySources(listOf(7L), lock = true) } returns listOf(
            ClusterLink(id = 17L, clusterId = 5L, feedbackSourceId = 7L, relationRole = "CANDIDATE",
                linkType = "CANDIDATE", candidateRank = 1, reviewStatus = "PENDING", reviewVersion = 0),
            ClusterLink(id = 18L, clusterId = 6L, feedbackSourceId = 7L, relationRole = "MAIN",
                linkType = "AUTO_HIGH", reviewStatus = "NOT_REQUIRED", reviewVersion = 0)
        )

        val error = assertThrows(ServiceException::class.java) {
            fixture.service.confirm(7L, 5L,
                ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        }

        assertEquals(ErrorCode.CLUSTER_MAIN_RACE, error.errorCode)
        assertEquals("CLUSTER_MAIN_CHANGED_DURING_LOCK", error.reasonCode)
        verify(exactly = 0) { fixture.repository.claimCandidates(any(), any()) }
        verify(exactly = 0) { fixture.marker.mark(any()) }
    }

    @Test
    fun `confirm marks cluster while skip never marks or recomputes statistics`() {
        val confirm = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        val command = ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题")

        confirm.service.confirm(7L, 5L, command, 9L)

        verify(exactly = 1) { confirm.statistics.recompute(listOf(5L)) }
        verify(exactly = 1) { confirm.marker.mark(match {
            it.sourceIds == listOf(7L) && it.reason.name == "CLUSTER"
        }) }

        val skip = reviewFixture("CLUSTER_REVIEW_SKIP")
        skip.service.skip(7L, ClusterReviewCommand(0, UUID.randomUUID().toString(), "暂时跳过复核"), 9L)

        verify(exactly = 0) { skip.statistics.recompute(any()) }
        verify(exactly = 0) { skip.marker.mark(any()) }
    }

    @Test
    fun `same request different command fails closed and completed replay avoids mutation`() {
        val conflict = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { conflict.ledger.claim(any(), any(), any(), any()) } returns ClusterLedgerClaim(
            1L, "CLUSTER_REVIEW_REJECT", "different", true, jacksonObjectMapper().readTree("{\"reviewed\":true}")
        )
        assertThrows(ServiceException::class.java) {
            conflict.service.confirm(7L, 5L,
                ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        }

        val replay = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { replay.ledger.claim(any(), any(), any(), any()) } answers {
            ClusterLedgerClaim(1L, secondArg(), thirdArg(), true,
                jacksonObjectMapper().readTree("{\"reviewed\":true}"))
        }
        replay.service.confirm(7L, 5L,
            ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        verify(exactly = 0) { replay.repository.lockClusters(any()) }
        verify(exactly = 0) { replay.marker.mark(any()) }
    }

    @Test
    fun `statistics audit and marker failures propagate from authoritative mutation`() {
        val statisticsFailure = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { statisticsFailure.statistics.recompute(any()) } throws IllegalStateException("statistics failed")
        assertThrows(IllegalStateException::class.java) {
            statisticsFailure.service.confirm(7L, 5L,
                ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        }

        val auditFailure = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { auditFailure.audits.append(any()) } throws IllegalStateException("audit failed")
        assertThrows(IllegalStateException::class.java) {
            auditFailure.service.confirm(7L, 5L,
                ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        }

        val markerFailure = reviewFixture("CLUSTER_REVIEW_CONFIRM")
        every { markerFailure.marker.mark(any()) } throws IllegalStateException("marker failed")
        assertThrows(IllegalStateException::class.java) {
            markerFailure.service.confirm(7L, 5L,
                ClusterReviewCommand(0, UUID.randomUUID().toString(), "确认同一问题"), 9L)
        }
    }

    @Test
    fun `approved kotlin paths contain no direct persistence and cluster xml stays single table`() {
        val kotlinFiles = listOf(
            "src/main/kotlin/com/feedback/controller/query/ClusterReviewController.kt",
            "src/main/kotlin/com/feedback/service/query/ClusterReviewService.kt",
            "src/main/kotlin/com/feedback/service/cluster/ClusterLifecycleService.kt",
            "src/main/kotlin/com/feedback/service/cluster/ClusteringEngine.kt",
            "src/main/kotlin/com/feedback/service/cluster/ClusterStatisticsService.kt",
            "src/main/kotlin/com/feedback/service/cluster/ClusterDecisionModel.kt"
        )
        kotlinFiles.forEach { file ->
            val source = Files.readString(Path.of(file))
            assertFalse(Regex("JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|PersistenceContext|nativeQuery").containsMatchIn(source), file)
            assertFalse(Regex("(?m)\\b(SELECT|UPDATE|INSERT|DELETE)\\s+(?:FROM|INTO|t_)").containsMatchIn(source), file)
        }
        val mybatis = Configuration().apply { isMapUnderscoreToCamelCase = true }
        val visited = mutableSetOf<String>()
        Files.list(Path.of("src/main/resources/mybatis/mapper/cluster")).use { files ->
            files.filter { it.fileName.toString().endsWith(".xml") }.forEach { file ->
                val source = Files.readString(file)
                val resource = "mybatis/mapper/cluster/${file.fileName}"
                val declared = READ_ONLY_JOIN_MAPPERS[file.fileName.toString()]
                if (declared == null) {
                    assertFalse(Regex("\\b(JOIN|EXISTS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(source), file.toString())
                    val tables = Regex("(?i)\\b(?:FROM|UPDATE|INTO)\\s+(t_[a-z0-9_]+)")
                        .findAll(source).map { it.groupValues[1].lowercase() }.toSet()
                    assertTrue(tables.size == 1, "$file accesses $tables")
                } else {
                    visited += file.fileName.toString()
                    assertFalse(Regex("(?i)<(insert|update|delete)\\b").containsMatchIn(source),
                        "$file 以「只读投影」登记在 READ_ONLY_JOIN_MAPPERS，却出现了写语句")
                    val tables = Regex("(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+(t_[a-z0-9_]+)")
                        .findAll(source).map { it.groupValues[1].lowercase() }.toSet()
                    assertEquals(declared, tables, "$file 读的表与登记的不一致")
                }
                Resources.getResourceAsReader(resource).use { reader ->
                    XMLMapperBuilder(reader, mybatis, resource, mybatis.sqlFragments).parse()
                }
            }
        }
        assertEquals(READ_ONLY_JOIN_MAPPERS.keys, visited, "READ_ONLY_JOIN_MAPPERS 里有登记了却扫不到的条目")
    }

    private data class ReviewFixture(
        val service: ClusterReviewService,
        val repository: ClusterMutationRepository,
        val ledger: ClusterCommandLedgerRepository,
        val statistics: ClusterStatisticsService,
        val audits: BusinessAuditService,
        val marker: FeedbackListProjectionDirtyMarker
    )

    private fun reviewFixture(operation: String): ReviewFixture {
        val repository = mockk<ClusterMutationRepository>(relaxed = true)
        val ledger = mockk<ClusterCommandLedgerRepository>(relaxed = true)
        val access = mockk<ClusterMutationAccessGuard>(relaxed = true)
        val statistics = mockk<ClusterStatisticsService>(relaxed = true)
        val audits = mockk<BusinessAuditService>(relaxed = true)
        val marker = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        every { ledger.claim(any(), any(), any(), any()) } answers {
            ClusterLedgerClaim(1L, secondArg(), thirdArg(), false, null)
        }
        every { repository.activeMainBySources(listOf(7L)) } returns emptyMap()
        every { repository.lockClusters(listOf(5L)) } returns listOf(
            com.feedback.model.cluster.Cluster(id = 5L, clusterName = "视频无响应", clusterStatus = "ACTIVE")
        )
        every { repository.linksBySources(listOf(7L), lock = true) } returns listOf(
            ClusterLink(
                id = 17L, clusterId = 5L, feedbackSourceId = 7L, relationRole = "CANDIDATE",
                linkType = "CANDIDATE", candidateRank = 1, reviewStatus = "PENDING", reviewVersion = 0
            )
        )
        val service = ClusterReviewService(
            repository, mockk(relaxed = true), ledger, access, mockk(relaxed = true), statistics,
            audits, marker, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            jacksonObjectMapper()
        )
        assertTrue(operation.startsWith("CLUSTER_REVIEW_"))
        return ReviewFixture(service, repository, ledger, statistics, audits, marker)
    }
}

/**
 * 允许 JOIN 的簇域 mapper，以及**它读的表**。
 *
 * 那条「单表、不许 JOIN」的规则管的是簇的**变更面**：一次命令一次单表写入。
 * 但预览读侧不是变更面 —— `ClusterShadowPreviewEvidenceMapper` 是三表 JOIN，
 * 而且是**必须有这个 JOIN**：脱敏规则要求非 PASS 或已删除的正文在**库这一层**就变成 NULL，
 * 拿单表查回来再在 JVM 里判会把未脱敏正文带进预览进程。
 *
 * 所以这里给的不是「文件名白名单」，而是**用可检验属性换来的豁免**：
 * 登记进来的文件必须零 `<insert|update|delete>`（下面的断言会把关），
 * 且读的表必须与登记集合**完全相等**（多读一张表也红）。
 * 谁往这个文件里加一条写语句，它立刻掉回单表规则 —— 这个豁免不是永久身份，是当前状态的证明。
 *
 * 早先这个文件是目录里唯一没有任何写语句的 mapper，这条对应关系是它自己长出来的，不是我们挑的。
 */
private val READ_ONLY_JOIN_MAPPERS = mapOf(
    "ClusterShadowPreviewEvidenceMapper.xml" to setOf(
        "t_cluster_shadow_candidate_member",
        "t_feedback_source",
        "t_feedback_content"
    )
)
