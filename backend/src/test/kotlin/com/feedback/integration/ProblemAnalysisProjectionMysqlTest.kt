package com.feedback.integration

import com.feedback.model.cluster.Cluster
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import com.feedback.mapper.feedback.listprojection.*
import com.feedback.model.po.feedback.listprojection.*
import com.feedback.repository.ClusterRepository
import com.feedback.repository.problem.analysis.ProblemAnalysisFactsRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import com.feedback.repository.config.SystemConfigRepository
import com.feedback.repository.SystemAuditLogRepository
import com.feedback.repository.auth.AuthDataPermissionRepository
import com.feedback.repository.feedback.detail.FeedbackDetailRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.impl.auth.DataPermissionServiceImpl
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.RoleCode
import com.feedback.model.auth.TenantKeys
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.nio.ByteBuffer
import java.util.UUID

/** 只允许显式连接 feedback_platform_test；测试数据通过正式 Mapper/Repository 写入并由事务回滚。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "dubbo.enabled=false",
        "dubbo.protocol.port=-1",
        "dubbo.registry.address=N/A"
    ]
)
@EnabledIfEnvironmentVariable(named = "FEEDBACK_ANALYSIS_MYSQL_ENABLED", matches = "true")
class ProblemAnalysisProjectionMysqlTest {
    @MockkBean(name = "feedbackAnalysisDynamicSystemConfigRepository", relaxed = true)
    lateinit var dynamicSystemConfigRepository: SystemConfigRepository
    @Autowired lateinit var generations: FeedbackListProjectionRepository
    @Autowired lateinit var members: FeedbackClusterMemberProjectionMapper
    @Autowired lateinit var identities: FeedbackClusterIdentityProjectionMapper
    @Autowired lateinit var projections: FeedbackListProjectionMapper
    @Autowired lateinit var clusters: ClusterRepository
    @Autowired lateinit var reads: ProblemAnalysisFactsRepository
    @Autowired lateinit var authPermissions: AuthDataPermissionRepository
    @Autowired lateinit var detailRepository: FeedbackDetailRepository
    @Autowired lateinit var auditLogs: SystemAuditLogRepository
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    @Transactional
    fun `dedicated mysql pages both lists and twenty feedback rows with logical channels and fingerprint counts`() {
        val firstCluster = clusters.save(Cluster(publicId = uuidBytes(), clusterName = "学习机与 Jira 同一问题", canonicalSymptom = "课程播放失败"))
        val secondCluster = clusters.save(Cluster(publicId = uuidBytes(), clusterName = "质量问题", canonicalSymptom = "设备屏幕闪烁"))
        val generation = generations.createBuilding("d-mysql-projection", 22, 22, 1)
        val now = LocalDateTime.of(2026, 8, 30, 12, 0)
        val firstMembers = (1L..21L).map { sourceId ->
            val sourceChannel = when (sourceId) { 1L -> "SDK"; 2L -> "STATION_FEEDBACK"; else -> "JIRA" }
            FeedbackClusterMemberProjectionPo(
                generationId = generation.id, sourceId = sourceId, clusterId = firstCluster.id,
                sourceChannel = sourceChannel,
                logicalChannel = if (sourceChannel in setOf("SDK", "STATION_FEEDBACK")) "LEARNING_MACHINE" else sourceChannel,
                clusterStatus = "ACTIVE", reportedAt = now.minusMinutes(sourceId), functionModule = "VIDEO",
                provinceCode = "11", cityCode = "1101", applicationName = "学习应用", regionValue = "北京",
                attachmentCount = 1, screenshotCount = 1
            )
        }
        val secondMember = FeedbackClusterMemberProjectionPo(
            generationId = generation.id, sourceId = 22, clusterId = secondCluster.id,
            sourceChannel = "QUALITY", logicalChannel = "QUALITY", clusterStatus = "ACTIVE", reportedAt = now,
            functionModule = "DISPLAY", provinceCode = "31", cityCode = "3101",
            applicationName = "设备", regionValue = "上海"
        )
        members.upsertAll(firstMembers + secondMember)
        projections.upsertAll((1L..22L).map { sourceId ->
            FeedbackListProjectionPo(
                generationId = generation.id, sourceId = sourceId,
                sourceChannel = if (sourceId == 22L) "QUALITY" else "JIRA", sourceRecordId = "D-$sourceId",
                processingStatus = "NEW", distributionStatus = "UNASSIGNED",
                description = "安全反馈 $sourceId", ingestTime = now
            )
        })
        identities.upsertAll(listOf(
            FeedbackClusterIdentityProjectionPo(
                generation.id, 1, firstCluster.id, "SDK", "LEARNING_MACHINE", "VIDEO", "11", "1101", "北京", now,
                "PERSON", "PHONE", "k1", ByteArray(32) { 1 }
            ),
            FeedbackClusterIdentityProjectionPo(
                generation.id, 1, firstCluster.id, "SDK", "LEARNING_MACHINE", "VIDEO", "11", "1101", "北京", now,
                "DEVICE", "DEVICE_SN", "k1", ByteArray(32) { 2 }
            )
        ))

        val criteria = AnalysisCriteria(
            listOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"), null, null, null, null
        )
        val totals = reads.totals(generation.id, criteria)
        val allSecondPage = reads.page(generation.id, criteria, false, 1, 1)
        val crossFirstPage = reads.page(generation.id, criteria, true, 1, 0)
        val identityCounts = reads.identities(generation.id, criteria, listOf(firstCluster.id)).getValue(firstCluster.id)
        val feedback = reads.feedbackPage(generation.id, criteria, firstCluster.id, 20, 0)
        val relatedOwn = reads.member(generation.id, criteria, 1)!!
        val related = reads.relatedPage(generation.id, criteria, relatedOwn.clusterId, 1, 10, 0)
        val relatedProjection = reads.feedbackProjections(generation.id, related.map { it.sourceId })

        assertEquals(2, totals.allTotal)
        assertEquals(1, totals.crossChannelTotal)
        assertEquals(1, allSecondPage.size)
        assertEquals(firstCluster.id, crossFirstPage.single().clusterId)
        assertEquals(2, crossFirstPage.single().logicalChannelCount)
        assertEquals(1, identityCounts.userCount)
        assertEquals(1, identityCounts.deviceCount)
        assertEquals(20, feedback.size)
        assertEquals(21, reads.feedbackCount(generation.id, criteria, firstCluster.id))
        assertEquals(20, reads.relatedCount(generation.id, criteria, firstCluster.id, 1))
        assertEquals((2L..11L).toList(), related.map { it.sourceId })
        assertEquals("安全反馈 2", relatedProjection.getValue(2).description)
    }

    @Test
    @Transactional
    fun authorization_mysql_role_scope_gate() {
        val service = DataPermissionServiceImpl(authPermissions)
        val permissionRows = authPermissions.findReadPermissions(9_876_543, setOf(RoleCode.PHASE_ONE_PRODUCT))
        // 主体租户是对照参数：本用例断言的是渠道维度的合并口径，三个主体都取同一个租户，
        // 于是租户这一维不参与区分（库里也没有 TENANT 授权行，见下条断言）。
        val productScope = service.scopeFor(9_876_543, setOf(RoleCode.PHASE_ONE_PRODUCT), TenantKeys.DEFAULT)
        val technicalScope = service.scopeFor(9_876_544, setOf(RoleCode.PHASE_ONE_TECHNICAL), TenantKeys.DEFAULT)
        val ordinaryUserScope = service.scopeFor(9_876_545, emptySet(), TenantKeys.DEFAULT)

        assertEquals(
            setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"),
            permissionRows.filter { it.scopeType == DataScopeType.CHANNEL && it.effect == DataPermissionEffect.ALLOW }
                .map { it.scopeValue }.toSet()
        )
        assertEquals(setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"), productScope.channels)
        assertEquals(setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"), technicalScope.channels)
        assertEquals(setOf("SDK"), ordinaryUserScope.channels)
    }

    @Test
    @Transactional
    fun audit_persistence_gate() {

        val sourceId = 8_765_432L
        detailRepository.appendViewAudit(
            9_876_543,
            "VIEW_EXTERNAL_SOURCE_LINK",
            sourceId,
            "{\"channel\":\"JIRA\",\"host\":\"jira.example\"}"
        )
        val audit = auditLogs.findAll().single {
            it.operation == "VIEW_EXTERNAL_SOURCE_LINK" && it.targetId == sourceId.toString()
        }
        assertEquals("FEEDBACK", audit.targetType)
        assertEquals("JIRA", audit.operationDetail.path("channel").asText())
        assertEquals("jira.example", audit.operationDetail.path("host").asText())
    }

    @Test
    fun transaction_rollback_gate() {
        val generationKey = "d-mysql-rollback-${UUID.randomUUID()}"
        val transaction = TransactionTemplate(transactionManager)

        transaction.executeWithoutResult { status ->
            generations.createBuilding(generationKey, 0, 0, 1)
            status.setRollbackOnly()
        }

        assertThrows(NoSuchElementException::class.java) {
            transaction.executeWithoutResult { generations.lockByKey(generationKey) }
        }
    }

    private fun uuidBytes(): ByteArray = UUID.randomUUID().let { uuid ->
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val url = System.getProperty(
                "phase.one.analysis.mysql.url",
                "jdbc:mysql://127.0.0.1:3306/feedback_platform_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
            )
            val database = url.substringBefore('?').substringAfterLast('/')
            check(database == "feedback_platform_test") { "FEEDBACK_ANALYSIS_MYSQL_DATABASE_FORBIDDEN:$database" }
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getProperty("phase.one.analysis.mysql.username", "root") }
            registry.add("spring.datasource.password") { System.getProperty("phase.one.analysis.mysql.password", "") }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.milvus.enabled") { "false" }
            registry.add("app.ai.embedding.provider") { "hashing" }
            registry.add("app.ai.enqueue-delay-ms") { "3600000" }
            registry.add("app.ai.worker-delay-ms") { "3600000" }
            registry.add("app.cluster.worker-delay-ms") { "3600000" }
            registry.add("app.sync.worker-delay-ms") { "3600000" }
            registry.add("app.masking.worker-delay-ms") { "3600000" }
            registry.add("app.imports.worker-delay-ms") { "3600000" }
            registry.add("app.station-feedback.worker-delay-ms") { "3600000" }
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("app.imports.raw-storage-secret") { "test-import-secret-at-least-32-characters" }
            registry.add("app.station-feedback.raw-storage-secret") { "test-station-secret-at-least-32-characters" }
        }
    }
}
