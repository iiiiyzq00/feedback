package com.feedback.integration

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.po.audit.*
import com.feedback.model.audit.*
import com.feedback.model.channel.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import com.feedback.model.po.config.*
import com.feedback.model.config.*
import com.feedback.model.po.feedback.*
import com.feedback.model.feedback.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import com.feedback.model.po.jira.*
import com.feedback.model.jira.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.*
import com.feedback.service.cluster.ClusteringEngine
import com.feedback.service.cluster.ClusterReclassificationService
import com.feedback.service.cluster.ClusterLifecycleService
import com.feedback.service.cluster.ClusterStatisticsService
import com.feedback.service.cluster.ProductPhenomenonFormatter
import com.feedback.service.cluster.SplitPartition
import com.feedback.service.query.ClusterReviewCommand
import com.feedback.service.query.ClusterReviewService
import com.feedback.security.WebPrincipal
import com.feedback.service.sync.DerivedIndexSyncWorker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "day4.mysql.enabled", matches = "true")
class ClusteringMysqlIntegrationTest {
    @Autowired lateinit var sources: FeedbackSourceRepository
    @Autowired lateinit var contents: FeedbackContentRepository
    @Autowired lateinit var ai: FeedbackAiRepository
    @Autowired lateinit var links: ClusterLinkRepository
    @Autowired lateinit var clusters: ClusterRepository
    @Autowired lateinit var engine: ClusteringEngine
    @Autowired lateinit var reclassification: ClusterReclassificationService
    @Autowired lateinit var lifecycle: ClusterLifecycleService
    @Autowired lateinit var reviews: ClusterReviewService
    @Autowired lateinit var statistics: ClusterStatisticsService
    @Autowired lateinit var indexWorker: DerivedIndexSyncWorker
    @Autowired private lateinit var probe: ClusteringIntegrationTestMapper
    private val json = jacksonObjectMapper()

    @Test
    @Transactional
    fun `cross-channel click and start operations stay distinct despite the same exception`() {
        val sdk = saveFeedback("SDK", "sdk-event-1", "AI助教点击后闪退 NullPointerException", "AI助教闪退")
        val jira = saveFeedback("JIRA", "PROJ-1234", "AI助教启动崩溃 NullPointerException", "AI助教闪退")

        val first = requireNotNull(engine.cluster(sdk.id))
        val second = requireNotNull(engine.cluster(jira.id))

        assertEquals("NEW_CLUSTER", first.linkType)
        assertEquals("NEW_CLUSTER", second.linkType)
        assertNotEquals(first.clusterId, second.clusterId)
        assertNotEquals(sdk.id, jira.id)
        assertEquals(first.clusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(sdk.id,"MAIN")?.clusterId)
        assertEquals(second.clusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(jira.id,"MAIN")?.clusterId)
        assertEquals(1, clusters.findById(first.clusterId).orElseThrow().totalRecordCount)
        val distribution = clusters.findById(first.clusterId).orElseThrow().channelDistribution.orEmpty()
        assertTrue(distribution.contains("SDK"))
        assertFalse(distribution.contains("JIRA"))
    }

    @Test
    @Transactional
    fun `hard module conflict creates different clusters`() {
        val tutor = saveFeedback("SDK", "sdk-event-2", "AI助教闪退", "闪退", "AI_TUTOR")
        val video = saveFeedback("JIRA", "PROJ-5678", "视频播放闪退", "闪退", "VIDEO")

        assertNotEquals(engine.cluster(tutor.id)?.clusterId, engine.cluster(video.id)?.clusterId)
    }

    @Test
    @Transactional
    fun `same primary problem across applications converges into one cluster`() {
        val tutor = saveFeedback("SDK", "same-name-tutor-${UUID.randomUUID()}", "设备没有网络", "设备没有网络", "AI_TUTOR")
        val video = saveFeedback("JIRA", "same-name-video-${UUID.randomUUID()}", "设备没有网络", "设备没有网络", "VIDEO")

        val tutorDecision = requireNotNull(engine.cluster(tutor.id))
        val videoDecision = requireNotNull(engine.cluster(video.id))

        assertEquals(tutorDecision.clusterId, videoDecision.clusterId)
        assertEquals("设备网络连接异常", clusters.findById(tutorDecision.clusterId).orElseThrow().clusterName)
        assertNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(tutor.id, "MAIN"))
        assertNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(video.id, "MAIN"))
    }

    @Test
    @Transactional
    fun `safe records without AI rows still join one main cluster`() {
        val first = saveFeedbackWithoutAi("SDK", "sdk-no-ai-1", "AI助教点击后闪退", "AI_TUTOR")
        val second = saveFeedbackWithoutAi("JIRA", "jira-no-ai-2", "打开AI助教，点击进入后应用闪退", "AI_TUTOR")

        assertFalse(ai.findById(first.id).isPresent)
        assertFalse(ai.findById(second.id).isPresent)
        val firstDecision = requireNotNull(engine.cluster(first.id))
        val secondDecision = requireNotNull(engine.cluster(second.id))

        assertEquals("NEW_CLUSTER", firstDecision.linkType)
        assertEquals("AUTO_HIGH", secondDecision.linkType)
        assertEquals(firstDecision.clusterId, secondDecision.clusterId)
        assertEquals(firstDecision.clusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(first.id, "MAIN")?.clusterId)
        assertEquals(firstDecision.clusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(second.id, "MAIN")?.clusterId)
    }

    @Test
    @Transactional
    fun `generic channel labels use safe description and ignore AI boundary drift`() {
        val first = saveGenericLabelFeedback(
            "generic-label-a-${UUID.randomUUID()}", "打开课程后视频持续黑屏", "CONSULT", "S1"
        )
        val same = saveGenericLabelFeedback(
            "generic-label-b-${UUID.randomUUID()}", "打开课程后视频持续黑屏", "BUG", "S4"
        )
        val different = saveGenericLabelFeedback(
            "generic-label-c-${UUID.randomUUID()}", "提交作业后页面一直转圈", "DEMAND", "S2"
        )
        val emptyHistorical = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "历史空簇",
            productScope = "LEARNING_MACHINE", canonicalSymptom = "打开课程后视频持续黑屏",
            totalRecordCount = 0, channelDistribution = "{}"
        ))

        val firstDecision = requireNotNull(engine.cluster(first.id))
        val sameDecision = requireNotNull(engine.cluster(same.id))
        val differentDecision = requireNotNull(engine.cluster(different.id))

        assertEquals(firstDecision.clusterId, sameDecision.clusterId)
        assertNotEquals(emptyHistorical.id, firstDecision.clusterId)
        assertEquals("AUTO_HIGH", sameDecision.linkType)
        assertNotEquals(firstDecision.clusterId, differentDecision.clusterId)
        assertEquals("NEW_CLUSTER", differentDecision.linkType)
        assertEquals("播放视频时无法正常播放", clusters.findById(firstDecision.clusterId).orElseThrow().canonicalSymptom)
    }

    @Test
    @Transactional
    fun `accepted causal member safely evolves the cluster name with audit evidence`() {
        val spinner = saveFeedback(
            "SDK", "name-evolution-a-${UUID.randomUUID()}",
            "提交作业后页面一直转圈", "提交作业后页面一直转圈", "HOMEWORK"
        )
        val incomplete = saveFeedback(
            "JIRA", "name-evolution-b-${UUID.randomUUID()}",
            "点击提交后无法完成提交", "点击提交后无法完成提交", "HOMEWORK"
        )

        val first = requireNotNull(engine.cluster(spinner.id))
        val second = requireNotNull(engine.cluster(incomplete.id))

        assertEquals(first.clusterId, second.clusterId)
        assertEquals("AUTO_HIGH", second.linkType)
        val evolved = clusters.findById(first.clusterId).orElseThrow()
        assertEquals("提交作业后页面持续转圈，导致作业无法完成提交", evolved.clusterName)
        assertEquals("提交作业后页面持续转圈，导致作业无法完成提交", evolved.canonicalSymptom)
        val evolutionEvidence = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(incomplete.id, "MAIN")!!.matchEvidence
        assertEquals("cluster-name-v1", json.readTree(evolutionEvidence).path("name_evolution").path("rule_version").asText())
    }

    @Test
    @Transactional
    fun `generic application samples separate distinct problems and keep true synonyms together`() {
        val network = saveGenericLabelFeedback(
            "generic-app-network-${UUID.randomUUID()}", "网络异常，不是第一次了", "BUG", "S3", "应用程序"
        )
        val freezeA = saveGenericLabelFeedback(
            "generic-app-freeze-a-${UUID.randomUUID()}", "一直在这里卡住", "BUG", "S3", "应用程序"
        )
        val freezeB = saveGenericLabelFeedback(
            "generic-app-freeze-b-${UUID.randomUUID()}", "一直在这儿卡死", "CONSULT", "S4", "应用程序"
        )
        val navigation = saveGenericLabelFeedback(
            "generic-app-navigation-${UUID.randomUUID()}", "跳不进去，老是跳不进去", "BUG", "S3", "应用程序"
        )
        val vagueA = saveGenericLabelFeedback(
            "generic-app-vague-a-${UUID.randomUUID()}", "非常不好。", "BUG", "S3", "应用程序"
        )
        val vagueB = saveGenericLabelFeedback(
            "generic-app-vague-b-${UUID.randomUUID()}", "非常不好。", "BUG", "S3", "应用程序"
        )
        val microphone = saveGenericLabelFeedback(
            "generic-app-microphone-${UUID.randomUUID()}", "麦克风", "BUG", "S3", "其他"
        )
        val video = saveGenericLabelFeedback(
            "generic-app-video-${UUID.randomUUID()}", "视频", "BUG", "S3", "其他"
        )

        val networkDecision = requireNotNull(engine.cluster(network.id))
        val freezeADecision = requireNotNull(engine.cluster(freezeA.id))
        val freezeBDecision = requireNotNull(engine.cluster(freezeB.id))
        val navigationDecision = requireNotNull(engine.cluster(navigation.id))
        val vagueADecision = requireNotNull(engine.cluster(vagueA.id))
        val vagueBDecision = requireNotNull(engine.cluster(vagueB.id))
        val microphoneDecision = requireNotNull(engine.cluster(microphone.id))
        val videoDecision = requireNotNull(engine.cluster(video.id))

        assertEquals(freezeADecision.clusterId, freezeBDecision.clusterId)
        assertEquals("AUTO_HIGH", freezeBDecision.linkType)
        val freezeNameKey = clusters.findById(freezeADecision.clusterId).orElseThrow().clusterNameKey
        assertEquals(1, probe.countLiveClustersByNameKey(freezeNameKey))
        assertEquals(2, links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(freezeA.id, freezeB.id), "MAIN").size)
        val vagueACanonical = clusters.findById(vagueADecision.clusterId).orElseThrow().canonicalSymptom
        val vagueBCanonical = clusters.findById(vagueBDecision.clusterId).orElseThrow().canonicalSymptom
        val vagueBReason = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(vagueB.id, "MAIN")!!.decisionReason
        assertEquals(
            vagueADecision.clusterId, vagueBDecision.clusterId,
            "canonicalA=$vagueACanonical canonicalB=$vagueBCanonical reasonB=$vagueBReason"
        )
        assertEquals(6, setOf(
            networkDecision.clusterId, freezeADecision.clusterId, freezeBDecision.clusterId, navigationDecision.clusterId,
            vagueADecision.clusterId, vagueBDecision.clusterId, microphoneDecision.clusterId, videoDecision.clusterId
        ).size)
        assertEquals(ProductPhenomenonFormatter.LOW_INFORMATION, vagueACanonical)
        assertEquals(ProductPhenomenonFormatter.LOW_INFORMATION, clusters.findById(vagueADecision.clusterId).orElseThrow().clusterName)
        assertEquals("使用麦克风时出现异常，但反馈未说明具体表现", clusters.findById(microphoneDecision.clusterId).orElseThrow().clusterName)
        assertEquals("播放视频时出现异常，但反馈未说明具体表现", clusters.findById(videoDecision.clusterId).orElseThrow().clusterName)
    }

    @Test
    @Transactional
    fun `same primary problem reopens its resolved cluster and every feedback keeps one main`() {
        val original = saveFeedback(
            "SDK", "resolved-recurrence-a-${UUID.randomUUID()}",
            "一到上课就卡顿", "一到上课就卡顿", "CLASSROOM"
        )
        val originalDecision = requireNotNull(engine.cluster(original.id))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), ""
        )
        lifecycle.transition(originalDecision.clusterId, "MITIGATING", command(0, "集成测试模拟缓解"), 1)
        lifecycle.transition(originalDecision.clusterId, "RESOLVED", command(1, "集成测试模拟已解决"), 1)
        val recurrence = saveFeedback(
            "JIRA", "resolved-recurrence-b-${UUID.randomUUID()}",
            "上课的时候一直卡顿", "上课的时候一直卡顿", "CLASSROOM"
        )

        val recurrenceDecision = requireNotNull(engine.cluster(recurrence.id))

        assertEquals(originalDecision.clusterId, recurrenceDecision.clusterId)
        assertEquals("REOPENED", clusters.findById(originalDecision.clusterId).orElseThrow().clusterStatus)
        val reopenedLinks = links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(original.id, recurrence.id), "MAIN")
        assertEquals(2, reopenedLinks.size)
        assertEquals(2, reopenedLinks.map { it.feedbackSourceId }.distinct().size)
        val reopenedNameKey = clusters.findById(originalDecision.clusterId).orElseThrow().clusterNameKey
        assertEquals(1, probe.countLiveClustersByNameKey(reopenedNameKey))
        assertEquals(1, probe.countAuditByOperation("CLUSTER_SYSTEM_REOPEN", originalDecision.clusterId.toString()))
        assertTrue(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(recurrence.id, "MAIN")!!.decisionReason!!.contains("重新打开"))
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `manual same problem name across applications reuses one cluster`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), ""
        )
        val tutor = saveFeedback(
            "SDK", "manual-name-tutor-${UUID.randomUUID()}",
            "设备没有网络", "设备没有网络", "AI_TUTOR"
        )
        val video = saveFeedback(
            "JIRA", "manual-name-video-${UUID.randomUUID()}",
            "设备无法联网", "设备无法联网", "VIDEO"
        )
        val candidate = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "人工候选-${UUID.randomUUID()}",
            productScope = "LEARNING_MACHINE", functionModule = "AI_TUTOR", issueCategory = "BUG"
        ))
        listOf(tutor, video).forEach { feedback ->
            links.save(ClusterLink(
                clusterId = candidate.id, feedbackSourceId = feedback.id, relationRole = "CANDIDATE",
                linkType = "CANDIDATE", reviewStatus = "PENDING", reviewVersion = 0, candidateRank = 1,
                reviewPriority = "P3", reviewDueAt = java.time.LocalDateTime.now().plusDays(3)
            ))
        }
        val sharedName = "设备网络连接异常-${UUID.randomUUID()}"
        val tutorClusterId = reviews.create(tutor.id, sharedName, command(0, "人工确认跨应用同一网络问题"), 1)
        val videoClusterId = reviews.create(video.id, sharedName, command(0, "另一应用复用同一网络问题簇"), 1)

        assertEquals(tutorClusterId, videoClusterId)
        assertEquals(tutorClusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(tutor.id, "MAIN")?.clusterId)
        assertEquals(tutorClusterId, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(video.id, "MAIN")?.clusterId)
        assertEquals(2, links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(tutor.id, video.id), "MAIN")
            .count { it.clusterId == tutorClusterId })
        assertEquals(1, probe.countLiveClustersByNormalizedName(sharedName))
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `manual create rejects a reused name when trigger boundaries conflict`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), ""
        )
        val click = saveFeedback(
            "SDK", "manual-trigger-click-${UUID.randomUUID()}",
            "点击助教后应用闪退", "点击助教后应用闪退", "AI_TUTOR"
        )
        val start = saveFeedback(
            "JIRA", "manual-trigger-start-${UUID.randomUUID()}",
            "启动助教时应用闪退", "启动助教时应用闪退", "AI_TUTOR"
        )
        val candidate = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "人工触发候选-${UUID.randomUUID()}",
            productScope = "LEARNING_MACHINE", functionModule = "AI_TUTOR", issueCategory = "BUG"
        ))
        listOf(click, start).forEach { feedback ->
            links.save(ClusterLink(
                clusterId = candidate.id, feedbackSourceId = feedback.id, relationRole = "CANDIDATE",
                linkType = "CANDIDATE", reviewStatus = "PENDING", reviewVersion = 0, candidateRank = 1,
                reviewPriority = "P3", reviewDueAt = java.time.LocalDateTime.now().plusDays(3)
            ))
        }
        val reusedName = "点击助教后应用闪退-${UUID.randomUUID()}"
        reviews.create(click.id, reusedName, command(0, "人工确认点击触发问题"), 1)

        val error = assertThrows(ClientException::class.java) {
            reviews.create(start.id, reusedName, command(0, "启动触发不能复用点击问题名称"), 1)
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("CLUSTER_NAME_DOES_NOT_COVER_PROFILE", error.reasonCode)
        assertNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(start.id, "MAIN"))
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `reclassification completes and rolls back isolated safe records`() {
        val first = saveFeedback("SDK", "reclass-sdk-${UUID.randomUUID()}", "重分类测试反馈一", "重分类测试反馈一", "RECLASSIFICATION")
        val second = saveFeedback("JIRA", "reclass-jira-${UUID.randomUUID()}", "重分类测试反馈二", "重分类测试反馈二", "RECLASSIFICATION")
        val requestId = UUID.randomUUID()

        val started = reclassification.start(requestId, "验证全量重分类闭环", 1L)
        assertEquals("RUNNING", started.runStatus)
        assertEquals(started.id, reclassification.start(requestId, "重复请求应幂等", 1L).id)

        repeat(4) {
            if (reclassification.findById(started.id)?.runStatus != "COMPLETED") reclassification.processBatch()
        }
        val completed = requireNotNull(reclassification.findById(started.id))
        assertEquals("COMPLETED", completed.runStatus)
        assertEquals(2L, completed.processedCount)
        assertEquals(2, links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(first.id, second.id), "MAIN")
            .count { it.reclassificationRunId == started.id })
        assertEquals(started.id, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(first.id, "MAIN")?.reclassificationRunId)
        assertEquals(started.id, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(second.id, "MAIN")?.reclassificationRunId)
        assertEquals(2, probe.countHashedAudit("CLUSTER_RECLASSIFICATION", started.id.toString(), 2))

        val rolledBack = reclassification.rollback(started.id, "验证可回滚", 1L)
        assertEquals("ROLLED_BACK", rolledBack.runStatus)
        val activeAfterRollback = links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(first.id, second.id), "MAIN")
        assertEquals(0, activeAfterRollback.count { it.reclassificationRunId == started.id })
        assertEquals(0, activeAfterRollback.size)
        assertEquals(3, probe.countHashedAudit("CLUSTER_RECLASSIFICATION", started.id.toString(), 2))
    }

    @Test
    @Transactional
    fun `reclassification removes inactive old clusters from exact recall`() {
        val first = saveFeedback("SDK", "reclass-exact-a-${UUID.randomUUID()}",
            "同步学其他错误", "同步学(其他错误)", "RECLASS_EXACT")
        val second = saveFeedback("JIRA", "reclass-exact-b-${UUID.randomUUID()}",
            "同步学其他错误", "同步学(其他错误)", "RECLASS_EXACT")
        val old = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "同步学(其他错误)",
            productScope = "LEARNING_MACHINE", functionModule = "RECLASS_EXACT", issueCategory = "BUG",
            canonicalSymptom = "同步学(其他错误)", totalRecordCount = 1, channelDistribution = "{\"SDK\":1}"
        ))
        links.saveAndFlush(ClusterLink(
            clusterId = old.id, feedbackSourceId = first.id, relationRole = "MAIN",
            linkType = "NEW_CLUSTER", reviewStatus = "NOT_REQUIRED"
        ))
        val olderEmpty = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "更早遗留空簇",
            productScope = "LEARNING_MACHINE", functionModule = "RECLASS_EXACT", issueCategory = "BUG",
            canonicalSymptom = "更早遗留空簇", totalRecordCount = 0, channelDistribution = "{}"
        ))
        links.saveAndFlush(ClusterLink(
            clusterId = olderEmpty.id, feedbackSourceId = second.id, relationRole = "MAIN",
            linkType = "NEW_CLUSTER", reviewStatus = "NOT_REQUIRED", isActive = false
        ))

        val run = reclassification.start(UUID.randomUUID(), "验证旧簇不污染精确召回", 1L)
        repeat(4) {
            if (reclassification.findById(run.id)?.runStatus != "COMPLETED") reclassification.processBatch()
        }

        assertEquals("COMPLETED", reclassification.findById(run.id)?.runStatus)
        val rebuilt = links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(first.id, second.id), "MAIN")
            .sortedBy { it.feedbackSourceId }.map { it.clusterId }
        assertEquals(2, rebuilt.size)
        assertEquals(1, rebuilt.distinct().size)
        assertNotEquals(old.id, rebuilt.first())
        assertEquals("ARCHIVED", clusters.findById(old.id).orElseThrow().clusterStatus)
        assertEquals("ARCHIVED", clusters.findById(olderEmpty.id).orElseThrow().clusterStatus)
    }

    @Test
    @Transactional
    fun `competing candidates conservatively become a new main association`() {
        val first=clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="候选边界A",
            productScope="LEARNING_MACHINE",functionModule="CANDIDATE_TEST",issueCategory="BUG",
            canonicalSymptom="提交作业后一直转圈",canonicalObject=null,canonicalTrigger="提交作业",totalRecordCount=0,channelDistribution="{}"))
        val second=clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="候选边界B",
            productScope="LEARNING_MACHINE",functionModule="CANDIDATE_TEST",issueCategory="BUG",
            canonicalSymptom="提交作业后一直转圈",canonicalObject=null,canonicalTrigger="提交作业",totalRecordCount=0,channelDistribution="{}"))
        val feedback=saveFeedback("SDK","sdk-candidate-boundary","提交作业后一直转圈","提交作业后一直转圈","CANDIDATE_TEST")

        val decision=requireNotNull(engine.cluster(feedback.id))

        assertEquals("NEW_CLUSTER",decision.linkType)
        val main = requireNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(feedback.id,"MAIN"))
        assertEquals(decision.clusterId, main.clusterId)
        assertTrue(main.clusterId != first.id && main.clusterId != second.id)
        assertEquals(1, clusters.findById(decision.clusterId).orElseThrow().totalRecordCount)
        assertEquals(0, listOf(first.id, second.id).sumOf { clusters.findById(it).orElseThrow().totalRecordCount })
        assertTrue(links.findAllByFeedbackSourceIdAndReviewStatus(feedback.id, "PENDING")
            .none { it.relationRole == "CANDIDATE" && it.isActive })
    }

    @Test
    @Transactional
    fun `merge and split preserve history single main and lineage`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), "")
        val a = clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="待合并A",productScope="LEARNING_MACHINE",functionModule="LIFECYCLE",issueCategory="BUG",totalRecordCount=1,channelDistribution="{\"SDK\":1}"))
        val b = clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="待合并B",productScope="LEARNING_MACHINE",functionModule="LIFECYCLE",issueCategory="BUG",totalRecordCount=1,channelDistribution="{\"JIRA\":1}"))
        val fa = saveFeedback("SDK", "lifecycle-a", "生命周期测试A", "生命周期测试A", "LIFECYCLE")
        val fb = saveFeedback("JIRA", "lifecycle-b", "生命周期测试B", "生命周期测试B", "LIFECYCLE")
        links.save(ClusterLink(clusterId=a.id,feedbackSourceId=fa.id,relationRole="MAIN",linkType="NEW_CLUSTER",reviewStatus="NOT_REQUIRED"))
        links.save(ClusterLink(clusterId=b.id,feedbackSourceId=fb.id,relationRole="MAIN",linkType="NEW_CLUSTER",
            featureSnapshot="{\"symptom\":\"生命周期测试B\"}",reviewStatus="NOT_REQUIRED"))

        lifecycle.merge(a.id, listOf(b.id), command(0, "合并边界一致的问题簇"), 1)
        assertEquals("MERGED", clusters.findById(b.id).orElseThrow().clusterStatus)
        assertEquals(a.id, clusters.findById(b.id).orElseThrow().mergedIntoClusterId)
        assertEquals(a.id, links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(fb.id,"MAIN")?.clusterId)
        val mergedFeature = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(fb.id, "MAIN")!!.featureSnapshot
        assertEquals("生命周期测试B", json.readTree(mergedFeature).path("symptom").asText())
        assertEquals(2, clusters.findById(a.id).orElseThrow().totalRecordCount)

        val collision = assertThrows(ClientException::class.java) {
            lifecycle.split(
                a.id,
                listOf(SplitPartition("待合并A", listOf(fa.id)), SplitPartition("拆分B", listOf(fb.id))),
                command(1, "拆分子簇不得复用现存问题簇名称"),
                1
            )
        }
        assertEquals(ErrorCode.BAD_REQUEST, collision.errorCode)
        assertEquals("CLUSTER_SPLIT_PARTITION_NAME_EXISTS_RENAME_REQUIRED", collision.reasonCode)
        assertEquals(2, links.findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(listOf(fa.id, fb.id), "MAIN")
            .count { it.clusterId == a.id })

        val children = lifecycle.split(a.id, listOf(SplitPartition("拆分A", listOf(fa.id)), SplitPartition("拆分B", listOf(fb.id))),
            command(1, "按互斥现象拆分并保留历史"), 1)
        assertEquals(2, children.size)
        assertEquals("SPLIT_ARCHIVED", clusters.findById(a.id).orElseThrow().clusterStatus)
        assertTrue(children.all { clusters.findById(it).orElseThrow().splitFromClusterId == a.id })
        assertNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(fa.id, "MAIN"))
        assertNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(fb.id, "MAIN"))
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `split rebuilds each child profile and rejects mixed hard boundaries`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), ""
        )
        val suffix = UUID.randomUUID().toString()
        val parent = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "待按真实画像拆分-$suffix",
            productScope = "LEARNING_MACHINE", functionModule = "AI_TUTOR", issueCategory = "BUG",
            canonicalSymptom = "助教异常", canonicalObject = "助教", canonicalTrigger = null,
            totalRecordCount = 3, channelDistribution = "{\"SDK\":2,\"JIRA\":1}"
        ))
        val click = saveFeedback("SDK", "split-click-$suffix", "点击助教后应用闪退", "点击助教后应用闪退", "AI_TUTOR")
        val start = saveFeedback("JIRA", "split-start-$suffix", "启动助教时应用闪退", "启动助教时应用闪退", "AI_TUTOR")
        val network = saveFeedback("SDK", "split-network-$suffix", "设备没有网络", "设备没有网络", "DEVICE")
        listOf(click, start, network).forEach { feedback ->
            links.save(ClusterLink(
                clusterId = parent.id, feedbackSourceId = feedback.id, relationRole = "MAIN",
                linkType = "NEW_CLUSTER", reviewStatus = "NOT_REQUIRED", problemFingerprint = "legacy-$suffix"
                , featureSnapshot = "{\"symptom\":\"legacy-parent-profile\"}"
            ))
        }

        val conflict = assertThrows(ClientException::class.java) {
            lifecycle.split(
                parent.id,
                listOf(
                    SplitPartition("混合触发动作-$suffix", listOf(click.id, start.id)),
                    SplitPartition("设备网络异常-$suffix", listOf(network.id))
                ),
                command(0, "同一子簇不得混入不同触发动作"),
                1
            )
        }
        assertEquals(ErrorCode.BAD_REQUEST, conflict.errorCode)
        assertEquals("CLUSTER_SPLIT_PARTITION_PROFILE_CONFLICT", conflict.reasonCode)

        val children = lifecycle.split(
            parent.id,
            listOf(
                SplitPartition("点击助教后应用闪退-$suffix", listOf(click.id)),
                SplitPartition("启动助教时应用闪退-$suffix", listOf(start.id)),
                SplitPartition("设备网络连接异常-$suffix", listOf(network.id))
            ),
            command(0, "按真实对象动作和故障模式拆分"),
            1
        )
        val childRows = children.associateWith { clusters.findById(it).orElseThrow() }
        val clickChild = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(click.id, "MAIN")!!
        val startChild = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(start.id, "MAIN")!!
        assertEquals("CLICK", com.feedback.service.cluster.ClusterProblemOntology.operationClass(
            childRows.getValue(clickChild.clusterId).canonicalTrigger
        ))
        assertEquals("START", com.feedback.service.cluster.ClusterProblemOntology.operationClass(
            childRows.getValue(startChild.clusterId).canonicalTrigger
        ))
        val networkChild = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(network.id, "MAIN")!!
        assertEquals("DEVICE", childRows.getValue(networkChild.clusterId).functionModule)
        val clickFeature = links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(click.id, "MAIN")!!.featureSnapshot
        assertEquals("legacy-parent-profile", json.readTree(clickFeature).path("symptom").asText())
        listOf(click, start, network).forEach { feedback ->
            val activeMain = requireNotNull(links.findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(feedback.id, "MAIN"))
            assertFalse(activeMain.problemFingerprint.isNullOrBlank())
        }
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `state machine rejects skipping mitigation`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), "")
        val cluster = clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="状态机测试",productScope="LEARNING_MACHINE"))
        val invalidTransition = assertThrows(ClientException::class.java) {
            lifecycle.transition(cluster.id, "RESOLVED", command(0, "禁止跳过缓解状态"), 1)
        }
        assertEquals(ErrorCode.BAD_REQUEST, invalidTransition.errorCode)
        assertEquals("CLUSTER_STATUS_TRANSITION_INVALID", invalidTransition.reasonCode)
        lifecycle.transition(cluster.id, "MITIGATING", command(0, "已开始执行缓解措施"), 1)
        lifecycle.transition(cluster.id, "RESOLVED", command(1, "缓解验证完成并解决"), 1)
        assertEquals("RESOLVED", clusters.findById(cluster.id).orElseThrow().clusterStatus)
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `manual merge rejects different trigger boundaries inside the same module`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), ""
        )
        val click = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "点击助教后应用闪退-${UUID.randomUUID()}",
            productScope = "LEARNING_MACHINE", functionModule = "AI_TUTOR", issueCategory = "BUG",
            canonicalSymptom = "点击助教后应用闪退", canonicalObject = "助教", canonicalTrigger = "点击助教"
        ))
        val start = clusters.save(Cluster(
            publicId = uuidBytes(UUID.randomUUID()), clusterName = "启动助教时应用闪退-${UUID.randomUUID()}",
            productScope = "LEARNING_MACHINE", functionModule = "AI_TUTOR", issueCategory = "BUG",
            canonicalSymptom = "启动助教时应用闪退", canonicalObject = "助教", canonicalTrigger = "启动助教"
        ))

        val error = assertThrows(ClientException::class.java) {
            lifecycle.merge(click.id, listOf(start.id), command(0, "不同触发阶段不能合并为同一问题"), 1)
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("CLUSTER_MERGE_PROFILE_CONFLICT", error.reasonCode)
        assertEquals("ACTIVE", clusters.findById(click.id).orElseThrow().clusterStatus)
        assertEquals("ACTIVE", clusters.findById(start.id).orElseThrow().clusterStatus)
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `reject and skip preserve review semantics versions and audits`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), emptySet()), "")
        val cluster=clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="审核动作测试簇",
            productScope="LEARNING_MACHINE",functionModule="REVIEW_ACTION",issueCategory="BUG"))
        val rejected=saveFeedback("SDK","review-reject","拒绝候选测试","拒绝候选测试","REVIEW_ACTION")
        val skipped=saveFeedback("JIRA","review-skip","稍后处理测试","稍后处理测试","REVIEW_ACTION")
        links.save(ClusterLink(clusterId=cluster.id,feedbackSourceId=rejected.id,relationRole="CANDIDATE",
            linkType="CANDIDATE",reviewStatus="PENDING",reviewPriority="P3",reviewDueAt=java.time.LocalDateTime.now().plusDays(3),reviewVersion=0,candidateRank=1))
        links.save(ClusterLink(clusterId=cluster.id,feedbackSourceId=skipped.id,relationRole="CANDIDATE",
            linkType="CANDIDATE",reviewStatus="PENDING",reviewPriority="P3",reviewDueAt=java.time.LocalDateTime.now().plusDays(3),reviewVersion=0,candidateRank=1))

        reviews.reject(rejected.id,command(0,"证据不足，拒绝全部候选"),1)
        val rejectedLink=links.findAllByFeedbackSourceIdAndRelationRoleAndIsActiveTrueOrderByCandidateRankAsc(rejected.id,"CANDIDATE")
        assertTrue(rejectedLink.isEmpty())
        assertEquals("REJECTED", links.findAllByFeedbackSourceIdAndReviewStatus(rejected.id, "REJECTED").single().reviewStatus)

        reviews.skip(skipped.id,command(0,"等待补充现场证据后处理"),1)
        val skippedLink = links.findAllByFeedbackSourceIdAndReviewStatus(skipped.id, "PENDING").single { it.relationRole == "CANDIDATE" }
        assertEquals(1L, skippedLink.reviewVersion)
        assertTrue(skippedLink.isActive)
        assertEquals(1, probe.countAuditByOperation("CLUSTER_REVIEW_REJECT", rejected.id.toString()))
        assertEquals(1, probe.countAuditByOperation("CLUSTER_REVIEW_SKIP", skipped.id.toString()))
        SecurityContextHolder.clearContext()
    }

    @Test
    @Transactional
    fun `statistics drift is repaired from active main and index outbox is durable`() {
        val feedback=saveFeedback("SDK","stats-index-consistency","统计与索引一致性","统计与索引一致性","CONSISTENCY")
        val cluster=clusters.save(Cluster(publicId=uuidBytes(UUID.randomUUID()),clusterName="一致性测试簇",productScope="LEARNING_MACHINE",functionModule="CONSISTENCY",issueCategory="BUG",totalRecordCount=99,channelDistribution="{\"JIRA\":99}"))
        links.saveAndFlush(ClusterLink(clusterId=cluster.id,feedbackSourceId=feedback.id,relationRole="MAIN",linkType="NEW_CLUSTER",reviewStatus="NOT_REQUIRED"))
        assertTrue(statistics.findDrift().any{it.clusterId==cluster.id})
        statistics.recomputeOne(cluster.id)
        assertEquals(1, clusters.findById(cluster.id).orElseThrow().totalRecordCount)
        assertTrue(clusters.findById(cluster.id).orElseThrow().channelDistribution!!.contains("SDK"))

        ai.save(ai.findById(feedback.id).orElseThrow().copy(searchIndexStatus = "PENDING", vectorIndexStatus = "PENDING"))
        indexWorker.seedOutbox()
        assertEquals(1, probe.countPendingIndexOutbox(feedback.id))
    }

    private fun command(version: Long, reason: String) = ClusterReviewCommand(version, UUID.randomUUID().toString(), reason)

    private fun saveFeedback(channel: String, recordId: String, description: String, summary: String, module: String = "AI_TUTOR"): FeedbackSource {
        val source = sources.save(FeedbackSource(
            publicId = uuidBytes(UUID.randomUUID()), sourceChannel = channel, sourceRecordId = recordId,
            sourceEventTime = java.time.LocalDateTime.now()
        ))
        contents.save(FeedbackContent(
            sourceId = source.id, description = description, functionModule = module,
            issueCategory = "BUG", maskingStatus = "PASS"
        ))
        ai.save(FeedbackAi(
            sourceId = source.id, aiSummary = summary, aiCategory = "BUG", aiSeverity = "S1",
            aiConfidence = BigDecimal("0.950000"), reviewStatus = "NOT_REQUIRED",
            searchIndexStatus = "PENDING", vectorIndexStatus = "PENDING"
        ))
        return source
    }

    private fun saveFeedbackWithoutAi(channel: String, recordId: String, description: String, module: String): FeedbackSource {
        val source = sources.save(FeedbackSource(
            publicId = uuidBytes(UUID.randomUUID()), sourceChannel = channel, sourceRecordId = recordId,
            sourceEventTime = java.time.LocalDateTime.now()
        ))
        contents.save(FeedbackContent(
            sourceId = source.id, description = description, functionModule = module,
            issueCategory = "BUG", maskingStatus = "PASS"
        ))
        return source
    }

    private fun saveGenericLabelFeedback(
        recordId: String,
        description: String,
        aiCategory: String,
        aiSeverity: String,
        title: String = "其他错误"
    ): FeedbackSource {
        val source = sources.save(FeedbackSource(
            publicId = uuidBytes(UUID.randomUUID()), sourceChannel = "STATION_FEEDBACK", sourceRecordId = recordId,
            sourceEventTime = java.time.LocalDateTime.now()
        ))
        contents.save(FeedbackContent(
            sourceId = source.id, title = title, description = description, maskingStatus = "PASS"
        ))
        ai.save(FeedbackAi(
            sourceId = source.id, aiSummary = "其他错误", aiCategory = aiCategory, aiSeverity = aiSeverity,
            aiConfidence = BigDecimal("0.950000"), reviewStatus = "NOT_REQUIRED",
            searchIndexStatus = "PENDING", vectorIndexStatus = "PENDING"
        ))
        return source
    }

    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getProperty("day4.mysql.url", "jdbc:mysql://localhost:3306/feedback_day4_cluster_it?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai")
            }
            registry.add("spring.datasource.username") { System.getProperty("day4.mysql.username", "root") }
            registry.add("spring.datasource.password") { System.getProperty("day4.mysql.password", "") }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.milvus.enabled") { "false" }
            registry.add("app.ai.embedding.provider") { "hashing" }
            registry.add("app.ai.enqueue-delay-ms") { "3600000" }
            registry.add("app.ai.worker-delay-ms") { "3600000" }
            registry.add("app.cluster.worker-delay-ms") { "3600000" }
            registry.add("app.sync.worker-delay-ms") { "3600000" }
            registry.add("app.sdk.app-secrets.course") { "test-course-secret-123" }
            registry.add("app.sdk.app-secrets.question-bank") { "test-question-secret-123" }
            registry.add("app.sdk.app-secrets.wrong-book") { "test-wrong-secret-123" }
            registry.add("app.sdk.app-secrets.ai-tutor") { "test-tutor-secret-123" }
            registry.add("app.sdk.app-secrets.app-store") { "test-store-secret-123" }
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("app.masking.development-candidate-version") { "" }
            registry.add("app.imports.raw-storage-secret") { "test-import-secret-at-least-32-characters" }
            registry.add("app.station-feedback.raw-storage-secret") { "test-station-raw-storage-secret-at-least-32-characters" }
            registry.add("dubbo.enabled") { "false" }
        }
    }
}

/**
 * 归簇 MySQL 集成测试的只读验证边界。业务写入仍走正式 Repository；这里只保留单表事实核验。
 */
@Mapper
internal interface ClusteringIntegrationTestMapper {
    fun countLiveClustersByNameKey(@Param("clusterNameKey") clusterNameKey: String): Int
    fun countLiveClustersByNormalizedName(@Param("clusterName") clusterName: String): Int
    fun countAuditByOperation(@Param("operation") operation: String, @Param("targetId") targetId: String): Int
    fun countHashedAudit(
        @Param("targetType") targetType: String,
        @Param("targetId") targetId: String,
        @Param("chainEpoch") chainEpoch: Int
    ): Int
    fun countPendingIndexOutbox(@Param("sourceId") sourceId: Long): Int
}
