package com.feedback.service.cluster

import com.feedback.model.cluster.Cluster
import com.feedback.model.cluster.ClusterLink
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import com.feedback.repository.applicationidentity.ApplicationIdentitySafeTextRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySafeText
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.model.applicationidentity.ApplicationResolutionStatus
import com.feedback.model.applicationidentity.PrimaryProblemClassificationStatus
import com.feedback.model.applicationidentity.PrimaryProblemFact
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.cluster.ClusterShadowInputFacts
import com.feedback.manager.applicationidentity.ApplicationIdentityResolver
import com.feedback.model.applicationidentity.ApplicationResolution
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatch
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

class ClusterHistoricalShadowServiceTest {
    @Test
    fun `safe text resolves one canonical application and closes multiple matches`() {
        val sources = mockk<ApplicationIdentitySourceRepository>()
        val identities = mockk<ApplicationIdentityRepository>()
        val resolver = mockk<ApplicationIdentityResolver>()
        val primaryFacts = mockk<PrimaryProblemFactRepository>()
        val safeTexts = mockk<ApplicationIdentitySafeTextRepository>()
        val inputs = listOf(
            ApplicationIdentitySourceInput(1, ApplicationSourceChannel.JIRA, emptyList(), null),
            ApplicationIdentitySourceInput(2, ApplicationSourceChannel.QUALITY, emptyList(), null),
            ApplicationIdentitySourceInput(3, ApplicationSourceChannel.REDBLACK, emptyList(), null)
        )
        val course = ApplicationIdentitySnapshot(11, "11111111-1111-4111-8111-111111111111", "知识课程", 1)
        val tutor = ApplicationIdentitySnapshot(12, "22222222-2222-4222-8222-222222222222", "AI学伴", 1)
        every { sources.nextFourChannelInputs(0, 3) } returns inputs
        every { identities.activeAliases(emptyList()) } returns emptyMap()
        every { identities.listAfter(null, 100) } returns listOf(course, tutor)
        every { primaryFacts.findBySourceIds(listOf(1L, 2L, 3L)) } returns emptyMap()
        every { safeTexts.findPassingBySourceIds(listOf(1L, 2L, 3L)) } returns mapOf(
            1L to ApplicationIdentitySafeText(null, "知识课程内容打不开，连接超时"),
            2L to ApplicationIdentitySafeText("硬件无法开机", null),
            3L to ApplicationIdentitySafeText("知识课程跳转AI学伴失败", null)
        )
        every { resolver.resolve(inputs[0], emptyMap()) } returns ApplicationResolution.Ambiguous(
            emptyList(), setOf(course.publicId, tutor.publicId)
        )
        every { resolver.resolve(inputs[1], emptyMap()) } returns ApplicationResolution.Unresolved
        every { resolver.resolve(inputs[2], emptyMap()) } returns ApplicationResolution.Unresolved

        val facts = HistoricalApplicationFactReader(
            sources, identities, resolver, primaryFacts, safeTexts, CanonicalApplicationTextMatcher()
        ).read(0, 3)

        assertEquals(course.publicId, facts[0].applicationKey)
        assertEquals(null, facts[1].applicationKey)
        assertFalse(facts[1].ambiguous)
        assertTrue(facts[2].ambiguous)
    }

    @Test
    fun `governed resolved facts provide cross channel identity while unresolved remains closed`() {
        val sources = mockk<ApplicationIdentitySourceRepository>()
        val identities = mockk<ApplicationIdentityRepository>()
        val resolver = mockk<ApplicationIdentityResolver>()
        val primaryFacts = mockk<PrimaryProblemFactRepository>()
        val safeTexts = mockk<ApplicationIdentitySafeTextRepository>()
        val textMatcher = mockk<CanonicalApplicationTextMatcher>()
        val inputs = listOf(
            ApplicationIdentitySourceInput(1, ApplicationSourceChannel.STATION_FEEDBACK, emptyList(), null),
            ApplicationIdentitySourceInput(2, ApplicationSourceChannel.JIRA, emptyList(), null),
            ApplicationIdentitySourceInput(3, ApplicationSourceChannel.REDBLACK, emptyList(), null)
        )
        every { sources.nextFourChannelInputs(0, 3) } returns inputs
        every { identities.activeAliases(emptyList()) } returns emptyMap()
        every { identities.listAfter(null, 100) } returns emptyList()
        every { safeTexts.findPassingBySourceIds(listOf(2L, 3L)) } returns emptyMap()
        // 读取器对每条输入都先用已获批别名解析一次；三条都没有别名，因此都落到「未解析」，
        // 再由下面的已审批主问题事实决定结果——跨渠道身份来自事实，不是来自解析器。
        inputs.forEach { every { resolver.resolve(it, emptyMap()) } returns ApplicationResolution.Unresolved }
        every { textMatcher.match(null, emptyList()) } returns CanonicalApplicationTextMatch.Unresolved
        every { primaryFacts.findBySourceIds(listOf(1L, 2L, 3L)) } returns mapOf(
            1L to primaryFact(1, "app-a", "RESOLVED"),
            2L to primaryFact(2, "app-a", "RESOLVED"),
            3L to primaryFact(3, null, "UNRESOLVED")
        )

        val facts = HistoricalApplicationFactReader(
            sources, identities, resolver, primaryFacts, safeTexts, textMatcher
        ).read(0, 3)

        assertEquals(listOf("app-a", "app-a", null), facts.map { it.applicationKey })
        assertEquals(listOf(false, false, false), facts.map { it.ambiguous })
        verify(exactly = 1) { resolver.resolve(inputs[0], emptyMap()) }
        verify(exactly = 1) { resolver.resolve(inputs[1], emptyMap()) }
        verify(exactly = 1) { resolver.resolve(inputs[2], emptyMap()) }
    }

    @Test
    fun `开关默认关闭且关闭时绝不读取历史数据`() {
        var reads = 0
        val port = HistoricalClusterShadowInputPort { _, _ ->
            reads++
            HistoricalClusterShadowBatch(emptyList(), 0, false)
        }

        assertThrows(ServiceException::class.java) {
            HistoricalClusterShadowService(port).evaluate(0, 100)
        }
        assertEquals(0, reads)
    }

    @Test
    fun `协调器每批只读取一次且相同快照重复执行不漂移`() {
        var reads = 0
        val batch = HistoricalClusterShadowBatch(
            records = listOf(
                record(1, 10, "播放视频时卡住"),
                record(2, 10, "播放视频没有声音"),
                record(3, 20, "video freezes during playback")
            ),
            nextSourceId = 3,
            mayHaveMore = true
        )
        val service = HistoricalClusterShadowService(HistoricalClusterShadowInputPort { after, limit ->
            assertEquals(0, after)
            assertEquals(3, limit)
            reads++
            batch
        }, enabled = true)

        val first = service.evaluate(0, 3)
        val second = service.evaluate(0, 3)

        assertEquals(first, second)
        assertEquals(2, reads)
        assertEquals(2, first.pairwiseChangedCount)
        assertEquals(1, first.pairwiseMergedCount)
        assertEquals(1, first.pairwiseSplitCount)
        assertEquals(2, first.currentClusterCount)
        assertEquals(2, first.proposedClusterCount)
    }

    @Test
    fun `游标和批量边界在读取前失败关闭`() {
        var reads = 0
        val service = HistoricalClusterShadowService(HistoricalClusterShadowInputPort { _, _ ->
            reads++
            HistoricalClusterShadowBatch(emptyList(), 0, false)
        }, enabled = true)

        assertThrows(ClientException::class.java) { service.evaluate(-1, 1) }
        assertThrows(ClientException::class.java) { service.evaluate(0, 0) }
        assertThrows(ClientException::class.java) { service.evaluate(0, 101) }
        assertEquals(0, reads)
    }

    @Test
    fun `应用歧义与未解析均进入非业务终态`() {
        val batch = HistoricalClusterShadowBatch(listOf(
            HistoricalClusterShadowInput(input(1, null, "播放视频时卡顿", ambiguous = true), null),
            HistoricalClusterShadowInput(input(2, null, "播放视频时卡顿"), null)
        ), 2, false)
        val result = HistoricalClusterShadowService(
            HistoricalClusterShadowInputPort { _, _ -> batch }, enabled = true
        ).evaluate(0, 2)

        assertEquals(2, result.nonActionableCount)
        assertEquals(ProblemClassificationStatus.APPLICATION_AMBIGUOUS, result.assignments[0].status)
        assertEquals(ProblemClassificationStatus.APPLICATION_UNRESOLVED, result.assignments[1].status)
        assertTrue(result.assignments.all { it.proposedClusterKey == null })
    }

    @Test
    fun `历史人工证据按审核人状态或类型任一命中即冻结`() {
        val applications = HistoricalApplicationFactPort { _, _ -> (1L..3L).map { id ->
            HistoricalApplicationFact(applicationSource(id), "alpha", "学习机", false)
        } }
        val profileFacts = mockk<ClusterProfileFactRepository>()
        val clusters = mockk<ClusterMutationRepository>()
        every { profileFacts.loadShadowSafe(listOf(1L, 2L, 3L)) } returns ClusterShadowInputFacts(
            sources = (1L..3L).associateWith { FeedbackSource(id = it, sourceChannel = "SDK") },
            contents = (1L..3L).associateWith {
                FeedbackContent(sourceId = it, title = "播放视频时卡顿", maskingStatus = "PASS")
            }
        )
        every { clusters.activeMainBySources(listOf(1L, 2L, 3L)) } returns mapOf(
            1L to ClusterLink(clusterId = 11, feedbackSourceId = 1, reviewedBy = 7),
            2L to ClusterLink(clusterId = 12, feedbackSourceId = 2, reviewStatus = "CONFIRMED"),
            3L to ClusterLink(clusterId = 13, feedbackSourceId = 3, linkType = "MANUAL_REASSIGNED")
        )
        every { clusters.clusters(listOf(11L, 12L, 13L)) } returns listOf(
            Cluster(id = 11, clusterName = "人工簇一"),
            Cluster(id = 12, clusterName = "人工簇二"),
            Cluster(id = 13, clusterName = "人工簇三")
        )
        val reader = HistoricalClusterShadowInputReader(applications, profileFacts, clusters)

        val batch = reader.read(0, 3)
        val result = HistoricalClusterShadowService(
            HistoricalClusterShadowInputPort { _, _ -> batch }, enabled = true
        ).evaluate(0, 3)

        assertTrue(result.assignments.all { it.manualLocked && it.decision == "MANUAL_LOCKED" })
        assertEquals(setOf("manual:11", "manual:12", "manual:13"),
            result.assignments.mapNotNull { it.proposedClusterKey }.toSet())
        verify(exactly = 1) { profileFacts.loadShadowSafe(listOf(1L, 2L, 3L)) }
        verify(exactly = 1) { clusters.activeMainBySources(listOf(1L, 2L, 3L)) }
        verify(exactly = 1) { clusters.clusters(listOf(11L, 12L, 13L)) }
    }

    @Test
    fun `review needed member is retained as terminal without a manual cluster`() {
        val applications = HistoricalApplicationFactPort { _, _ -> listOf(
            HistoricalApplicationFact(
                ApplicationIdentitySourceInput(1, ApplicationSourceChannel.REDBLACK, emptyList(), null),
                null, null, false
            )
        ) }
        val profileFacts = mockk<ClusterProfileFactRepository>()
        val clusters = mockk<ClusterMutationRepository>()
        every { profileFacts.loadShadowSafe(listOf(1L)) } returns ClusterShadowInputFacts(
            sources = mapOf(1L to FeedbackSource(id = 1, sourceChannel = "REDBLACK")),
            contents = mapOf(1L to FeedbackContent(
                sourceId = 1, title = null, description = null, maskingStatus = "REVIEW_NEEDED"
            ))
        )
        every { clusters.activeMainBySources(listOf(1L)) } returns mapOf(
            1L to ClusterLink(clusterId = 11, feedbackSourceId = 1, reviewedBy = 7)
        )
        every { clusters.clusters(listOf(11L)) } returns listOf(Cluster(id = 11, clusterName = "existing cluster"))

        val batch = HistoricalClusterShadowInputReader(applications, profileFacts, clusters).read(0, 1)
        val result = HistoricalClusterShadowService(HistoricalClusterShadowInputPort { _, _ -> batch }, enabled = true)
            .evaluate(0, 1)

        assertEquals(1, result.processedCount)
        assertEquals(0, result.actionableCount)
        assertEquals(mapOf("REDBLACK" to 1), result.memberCountByChannel)
        assertEquals(ProblemClassificationStatus.CONTENT_REVIEW_NEEDED, result.assignments.single().status)
        assertEquals(null, result.assignments.single().proposedClusterKey)
        assertFalse(result.assignments.single().manualLocked)
    }

    @Test
    fun `数据库读取器是只读事务而协调器不持有事务`() {
        val read = HistoricalClusterShadowInputReader::class.java
            .getMethod("read", java.lang.Long.TYPE, Integer.TYPE)
            .getAnnotation(Transactional::class.java)
        assertTrue(read.readOnly)
        assertFalse(HistoricalClusterShadowService::class.java.isAnnotationPresent(Transactional::class.java))
        assertFalse(HistoricalClusterShadowService::class.java.methods
            .single { it.name == "evaluate" }.isAnnotationPresent(Transactional::class.java))
    }

    private fun record(sourceId: Long, currentClusterId: Long?, text: String) =
        HistoricalClusterShadowInput(input(sourceId, "alpha", text), currentClusterId)

    private fun input(sourceId: Long, applicationKey: String?, text: String, ambiguous: Boolean = false) =
        ClusterClassificationInput(
            sourceId = sourceId,
            applicationKey = applicationKey,
            applicationName = applicationKey?.let { "学习机" },
            title = text,
            description = null,
            applicationAmbiguous = ambiguous
        )

    private fun applicationSource(sourceId: Long) = ApplicationIdentitySourceInput(
        sourceId = sourceId,
        sourceChannel = ApplicationSourceChannel.SDK,
        aliases = emptyList(),
        displayNameEvidence = null
    )

    private fun primaryFact(sourceId: Long, publicId: String?, status: String) = PrimaryProblemFact(
        feedbackSourceId = sourceId,
        applicationIdentityId = publicId?.let { sourceId },
        applicationPublicIdSnapshot = publicId,
        applicationDisplayNameSnapshot = publicId?.let { "approved display" },
        applicationMappingVersion = publicId?.let { 1 },
        // 构造的是仓储契约返回的领域类型，不是 PO：字符串按 MyBatis 实现里
        // 同一套 fromCode/valueOf 规则转换，避免测试自造一条与生产不同的转换路径。
        applicationResolutionStatus = ApplicationResolutionStatus.fromCode(status),
        classificationStatus = PrimaryProblemClassificationStatus.PENDING,
        resolutionRuleVersion = "application-identity-v1",
        resolutionReasonCode = if (publicId == null) "APPLICATION_ALIAS_MISSING" else null,
        resolvedAliasCount = if (publicId == null) 0 else 1
    )
}
