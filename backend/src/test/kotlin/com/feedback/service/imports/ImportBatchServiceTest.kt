package com.feedback.service.imports

import com.feedback.repository.sdk.ChannelSdkRepository

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
import com.feedback.repository.imports.ImportRollbackRepository
import com.feedback.service.masking.*
import com.feedback.service.normalization.EnumMappingRegistry
import com.feedback.service.jira.JiraAssigneeOwnerInitializer
import com.feedback.service.jira.JiraOwnerInitializationResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.time.LocalDateTime

class ImportBatchServiceTest {
    @Test
    fun `jira offset timestamps are stored in configured business timezone`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(
            csv("id,description,created,updated\nJ-TIME-1,课程无法打开,2026-08-17T09:07:26.000+08:00,2026-08-18T09:25:55.000+08:00"),
            "JIRA", null
        )
        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description",
            "source_event_time" to "created", "source_update_time" to "updated"
        ))

        fixture.service.processBatch(upload.batchId)

        assertEquals(LocalDateTime.parse("2026-08-17T09:07:26"), fixture.savedSources.single().sourceEventTime)
        assertEquals(LocalDateTime.parse("2026-08-18T09:25:55"), fixture.savedSources.single().sourceUpdateTime)
    }

    @Test
    fun `uploads csv for all three channels with masked preview and mapping recommendation`() {
        listOf("JIRA", "QUALITY", "REDBLACK").forEach { channel ->
            val fixture = Fixture()
            val file = csv("issue key,description,updated\n${channel}-1,联系13812345678,2026-07-27T10:00:00")

            val result = fixture.service.upload(file, channel, null)

            assertEquals("NEW", result.duplicateResult)
            assertEquals(1, result.totalRows)
            assertEquals("issue key", result.recommendedMapping["source_record_id"])
            assertEquals("description", result.recommendedMapping["description"])
            assertFalse(result.preview.flatten().any { it.contains("13812345678") })
            assertTrue(result.preview.flatten().any { it.contains("[PHONE]") })
        }
    }

    @Test
    fun `detects exact duplicate without creating another batch`() {
        val fixture = Fixture()
        val file = csv("id,description\nJ-1,普通反馈")
        val first = fixture.service.upload(file, "JIRA", null)
        fixture.exactDuplicate = fixture.savedBatches.single()

        val duplicate = fixture.service.upload(file, "JIRA", null)

        assertEquals(first.batchId, duplicate.batchId)
        assertEquals("EXACT_DUPLICATE", duplicate.duplicateResult)
        assertEquals(1, fixture.savedBatches.size)
    }

    @Test
    fun `confirm persists unified feedback and jira extension then destroys raw row`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("issue key,description,status\nJIRA-7,页面崩溃,Open"), "JIRA", null)

        val queued = fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "issue key", "description" to "description", "status" to "status"
        ))
        assertEquals("PROCESSING", queued.status)
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals(1, result.successRows)
        assertEquals("IMPORTED", fixture.dataRows().single().rowStatus)
        assertNull(fixture.dataRows().single().rawRowContentEnc)
        assertNotNull(fixture.dataRows().single().rawDeletedAt)
        verify(exactly = 1) { fixture.contents.save(match { it.description == "页面崩溃" && it.maskingStatus == "PASS" }) }
        verify(exactly = 1) { fixture.jira.save(match { it.jiraKey == "JIRA-7" }) }
    }

    @Test
    fun `jira workflow identities keep display names while free form content remains masked`() {
        val fixture = Fixture(object : NerMasker {
            override fun mask(text: String): NerMaskingResult = NerMaskingResult(
                text.replace("张三", "[NAME_REMOVED]").replace("李四", "[NAME_REMOVED]"),
                listOf("张三", "李四").filter(text::contains).map { MaskingFinding("NAME", it) },
                true
            )
        })
        val upload = fixture.service.upload(
            csv("id,description,assignee,reporter,creator,lead\nJ-IDENTITY-1,学生张三反馈无法进入课堂,张三,李四,王五,赵六"),
            "JIRA", null
        )
        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description", "assignee" to "assignee",
            "reporter" to "reporter", "creator" to "creator", "project_lead" to "lead"
        ))

        fixture.service.processBatch(upload.batchId)

        verify { fixture.contents.save(match { it.description!!.contains("[NAME_REMOVED]") }) }
        verify { fixture.jira.save(match {
            it.assignee == "张三" && it.reporter == "李四" && it.creator == "王五" && it.projectLead == "赵六"
        }) }
    }

    @Test fun `large batch commits in bounded 500 row slices`() {
        val fixture=Fixture()
        val body=buildString{appendLine("id,description");(1..501).forEach{appendLine("SLICE-$it,隔离样本 $it")}}
        val upload=fixture.service.upload(csv(body),"JIRA",null)
        fixture.service.confirm(upload.batchId,mapOf("source_record_id" to "id","description" to "description"))

        val first=fixture.service.processBatch(upload.batchId)
        val second=fixture.service.processBatch(upload.batchId)

        assertEquals("PROCESSING",first.status)
        assertEquals(500,first.successRows)
        assertEquals("COMPLETED",second.status)
        assertEquals(501,second.successRows)
    }

    @Test
    fun `five hundred Jira rows initialize owners in three bounded calls after generated ids are returned`() {
        val initializer = mockk<JiraAssigneeOwnerInitializer>()
        every { initializer.initializeBatch(any()) } answers {
            val ids = firstArg<List<Long>>()
            JiraOwnerInitializationResult(ids.size, ids, emptyList())
        }
        val fixture = Fixture(jiraOwnerInitializer = initializer)
        val body = buildString {
            appendLine("id,description,assignee")
            (1..500).forEach { appendLine("OWNER-$it,负责人批量样本 $it,王工") }
        }
        val upload = fixture.service.upload(csv(body), "JIRA", null)
        fixture.service.confirm(
            upload.batchId,
            mapOf("source_record_id" to "id", "description" to "description", "assignee" to "assignee")
        )

        fixture.service.processBatch(upload.batchId)

        verifySequence {
            initializer.initializeBatch((100L..299L).toList())
            initializer.initializeBatch((300L..499L).toList())
            initializer.initializeBatch((500L..599L).toList())
        }
        verify(exactly = 0) { initializer.initialize(any()) }
    }

    @Test
    fun `Jira owner batch failure propagates and stops remaining chunks`() {
        val initializer = mockk<JiraAssigneeOwnerInitializer>()
        every { initializer.initializeBatch((100L..299L).toList()) } returns JiraOwnerInitializationResult(
            200,
            (100L..299L).toList(),
            emptyList()
        )
        every { initializer.initializeBatch((300L..300L).toList()) } throws IllegalStateException("OWNER_BATCH_FAILED")
        val fixture = Fixture(jiraOwnerInitializer = initializer)
        val body = buildString {
            appendLine("id,description,assignee")
            (1..201).forEach { appendLine("OWNER-FAIL-$it,负责人失败样本 $it,王工") }
        }
        val upload = fixture.service.upload(csv(body), "JIRA", null)
        fixture.service.confirm(
            upload.batchId,
            mapOf("source_record_id" to "id", "description" to "description", "assignee" to "assignee")
        )

        val error = assertThrows(IllegalStateException::class.java) { fixture.service.processBatch(upload.batchId) }

        assertEquals("OWNER_BATCH_FAILED", error.message)
        verify(exactly = 2) { initializer.initializeBatch(any()) }
        verify(exactly = 0) { initializer.initialize(any()) }
    }

    @Test
    fun `residual long number is rule masked and imported without review`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description\nR-1,未知编号 1234567890 需要复核"), "REDBLACK", null)
        val mapping = mapOf("source_record_id" to "id", "description" to "description")

        fixture.service.confirm(upload.batchId, mapping)
        val first = fixture.service.processBatch(upload.batchId)
        assertEquals("COMPLETED", first.status)
        assertEquals("IMPORTED", fixture.dataRows().single().rowStatus)
        verify { fixture.sources.save(any()) }
        verify { fixture.contents.save(match { it.maskingStatus == "PASS" && it.description?.contains("[LONG_NUMBER_REMOVED]") == true }) }
        verify { fixture.redblack.save(any()) }
    }

    @Test
    fun `formal re-pull auto imports when current rules produce safe text`() {
        val fixture = Fixture()
        val mapping = mapOf("source_record_id" to "id", "description" to "description", "source_update_time" to "updated")
        val batch = fixture.service.stageJiraApiBatch(
            listOf(mapOf("id" to "SPFB-1", "description" to "普通反馈", "updated" to "2026-08-19T10:00:00")),
            10L, "v1.0.0", mapping, "FORMAL_REVIEW",
            LocalDateTime.parse("2026-08-18T00:00:00"), LocalDateTime.parse("2026-08-19T00:00:00"), ByteArray(32), null
        )

        val result = fixture.service.processBatch(batch.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals("IMPORTED", fixture.dataRows().single().rowStatus)
        assertNull(fixture.dataRows().single().qualityErrorCode)
        verify { fixture.sources.save(any()) }
        verify { fixture.contents.save(match { it.maskingStatus == "PASS" }) }
    }

    @Test
    fun `jira api row with long number auto masks without review retry`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description\nJ-API-1,未知编号 1234567890 需要复核"), "JIRA", null)
        fixture.service.confirm(upload.batchId, mapOf("source_record_id" to "id", "description" to "description"))
        assertEquals("COMPLETED", fixture.service.processBatch(upload.batchId).status)
        val current = fixture.savedBatches.single()
        fixture.savedBatches[0] = current.copy(ingestMethod = "API")
        assertEquals("IMPORTED", fixture.dataRows().single().rowStatus)
        verify { fixture.contents.save(match { it.description?.contains("[LONG_NUMBER_REMOVED]") == true }) }
    }

    @Test
    fun `changed mapping on failed batch publishes a new version while unchanged mapping reuses it`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(
            csv("id,description,alternate,updated\nJ-CHANGE,原描述,替代描述,not-a-time"), "JIRA", null
        )
        val original = mapOf(
            "source_record_id" to "id", "description" to "description", "title" to "alternate",
            "source_update_time" to "updated"
        )
        fixture.service.confirm(upload.batchId, original)
        assertEquals("PARTIAL_SUCCESS", fixture.service.processBatch(upload.batchId).status)

        fixture.service.confirm(upload.batchId, original)
        verify(exactly = 1) { fixture.templateContracts.publishNext("JIRA", any(), any(), any()) }
        assertEquals("PARTIAL_SUCCESS", fixture.service.processBatch(upload.batchId).status)

        val changed = original + mapOf("description" to "alternate", "title" to "description")
        fixture.service.confirm(upload.batchId, changed)
        verify(exactly = 2) { fixture.templateContracts.publishNext("JIRA", any(), any(), any()) }
        assertEquals("v1.0.1", fixture.savedBatches.single().mappingConfigVersion)
        assertTrue(fixture.savedBatches.single().enumMappingVersion.orEmpty().startsWith("enumset-"))
    }

    @Test
    fun `rollback rejects batches older than 24 hours`() {
        val fixture = Fixture()
        val old = fixture.newBatch(createdAt = LocalDateTime.now().minusHours(25))

        val error = assertThrows(ClientException::class.java) { fixture.service.rollback(old.batchId) }

        assertEquals("IMPORT_VALIDATION_FAILED",error.reasonCode)
    }

    @Test
    fun `jira duplicate row is skipped by business key and fingerprint`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description,updated\nJ-9,相同内容,2026-07-27T10:00:00"), "JIRA", null)
        fixture.existingSource = FeedbackSource(id = 88, sourceChannel = "JIRA", sourceRecordId = "J-9")
        fixture.previousRow = ImportRow(
            id = 999, batchId = 999, recordType = "ROW", feedbackSourceId = 88,
            rowFingerprint = fixture.dataRows().single().rowFingerprint
        )
        fixture.existingContent = FeedbackContent(sourceId = 88, maskingStatus = "PASS")

        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description", "source_update_time" to "updated"
        ))
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals(1, result.skippedRows)
        assertEquals("SKIPPED_DUPLICATE", fixture.dataRows().single().rowStatus)
        verify(exactly = 0) { fixture.contents.save(any()) }
    }

    @Test
    fun `jira duplicate snapshot refreshes legacy masked workflow identities`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(
            csv("id,description,updated,assignee\nJ-IDENTITY-REFRESH,相同内容,2026-07-27T10:00:00,张三"),
            "JIRA", null
        )
        fixture.existingSource = FeedbackSource(
            id = 88, sourceChannel = "JIRA", sourceRecordId = "J-IDENTITY-REFRESH",
            sourceUpdateTime = LocalDateTime.parse("2026-07-27T10:00:00")
        )
        fixture.previousRow = ImportRow(
            id = 999, batchId = 999, recordType = "ROW", feedbackSourceId = 88,
            rowFingerprint = fixture.dataRows().single().rowFingerprint
        )
        fixture.existingContent = FeedbackContent(sourceId = 88, maskingStatus = "PASS")
        fixture.existingJira = ChannelJira(
            sourceId = 88, jiraBaseUrlHash = ByteArray(32), jiraKey = "J-IDENTITY-REFRESH",
            assignee = "[NAME_REMOVED]"
        )
        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description",
            "source_update_time" to "updated", "assignee" to "assignee"
        ))

        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals("UPDATED", fixture.dataRows().single().rowStatus)
        verify { fixture.jira.save(match { it.sourceId == 88L && it.assignee == "张三" }) }
    }

    @Test
    fun `jira duplicate quarantined snapshot is skipped before a formal source exists`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description,updated\nJ-REVIEW,序列号 20260814123456,2026-08-14T10:00:00"), "JIRA", null)
        fixture.previousQuarantinedRow = ImportRow(
            id = 996, batchId = 996, recordType = "ROW", rowStatus = "REVIEW_NEEDED",
            rowFingerprint = fixture.dataRows().single().rowFingerprint
        )

        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description", "source_update_time" to "updated"
        ))
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals(0, result.reviewedRows)
        assertEquals(1, result.skippedRows)
        assertEquals("SKIPPED_DUPLICATE", fixture.dataRows().single().rowStatus)
        assertEquals(null, fixture.dataRows().single().feedbackSourceId)
        verify(exactly = 0) { fixture.contents.save(any()) }
    }

    @Test
    fun `identical jira snapshot reprocesses content quarantined by a newer masking rule`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description,updated\nJ-REDACT,序列号 20260814123456,2026-08-14T10:00:00"), "JIRA", null)
        fixture.existingSource = FeedbackSource(
            id = 90, sourceChannel = "JIRA", sourceRecordId = "J-REDACT",
            sourceUpdateTime = LocalDateTime.parse("2026-08-14T10:00:00")
        )
        fixture.previousRow = ImportRow(
            id = 997, batchId = 997, recordType = "ROW", feedbackSourceId = 90,
            rowFingerprint = fixture.dataRows().single().rowFingerprint
        )
        fixture.existingContent = FeedbackContent(sourceId = 90, maskingStatus = "REVIEW_NEEDED")

        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description", "source_update_time" to "updated"
        ))
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals("UPDATED", fixture.dataRows().single().rowStatus)
        verify { fixture.contents.save(match { it.sourceId == 90L && it.maskingStatus == "PASS" && !it.description.orEmpty().contains("20260814123456") }) }
    }

    @Test
    fun `older jira snapshot is skipped stale`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description,updated\nJ-10,旧内容,2026-07-26T10:00:00"), "JIRA", null)
        fixture.existingSource = FeedbackSource(
            id = 89, sourceChannel = "JIRA", sourceRecordId = "J-10",
            sourceUpdateTime = LocalDateTime.parse("2026-07-27T10:00:00")
        )
        fixture.previousRow = ImportRow(id = 998, batchId = 998, recordType = "ROW", feedbackSourceId = 89, rowFingerprint = ByteArray(32) { 1 })

        fixture.service.confirm(upload.batchId, mapOf(
            "source_record_id" to "id", "description" to "description", "source_update_time" to "updated"
        ))
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals(1, result.skippedRows)
        assertEquals("SKIPPED_STALE", fixture.dataRows().single().rowStatus)
        verify(exactly = 0) { fixture.contents.save(any()) }
    }

    @Test
    fun `quality import derives source record id but does not persist exact address`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("报修区域,客户地址,故障现象确认,故障现象备注,申请类型,商返申请日期\n华东,上海市某某路418号,充电口松动,客户反馈无法充电,换机,2026-04-27 17:27:20"), "QUALITY", null)

        fixture.service.confirm(upload.batchId, mapOf(
            "repair_region" to "报修区域",
            "customer_address" to "客户地址",
            "fault_confirmed" to "故障现象确认",
            "title" to "故障现象确认",
            "description" to "故障现象备注",
            "application_type" to "申请类型",
            "source_event_time" to "商返申请日期"
        ))
        fixture.dataRows().first().apply {
            qualityStage = "IDEMPOTENCY"
            qualityErrorCode = "IDEMPOTENCY_CONFLICT"
            qualityErrorSummary = "旧失败事实"
            qualitySuggestion = "旧处理建议"
        }
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        val source = fixture.savedSources.single()
        assertTrue(source.sourceRecordId.startsWith("QUALITY-"))
        assertEquals(40, source.sourceRecordId.length)
        val quality = fixture.savedQuality.single()
        assertEquals("华东", quality.repairRegion)
        assertEquals(null, quality.customerAddress)
        assertEquals("充电口松动", quality.faultConfirmed)
    }

    @Test
    fun `quality import uses return order and device together as business key`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("商返单号,机身号码,客户地址,故障现象确认,故障现象备注,商返申请日期\nRF-1001,SN-A001,同一地址,无法开机,设备A无法开机,2026-05-21\nRF-1001,SN-A002,同一地址,无法开机,设备B无法开机,2026-05-21"), "QUALITY", null)

        fixture.service.confirm(upload.batchId, mapOf(
            "return_order_no" to "商返单号",
            "device_sn" to "机身号码",
            "customer_address" to "客户地址",
            "fault_confirmed" to "故障现象确认",
            "description" to "故障现象备注",
            "source_event_time" to "商返申请日期"
        ))
        val result = fixture.service.processBatch(upload.batchId)

        assertEquals("COMPLETED", result.status)
        assertEquals(2, fixture.savedSources.size)
        assertEquals(2, fixture.savedSources.map { it.sourceRecordId }.distinct().size)
        assertTrue(fixture.savedSources.all { it.sourceRecordId.startsWith("QUALITY-") && it.sourceRecordId.length == 40 })
        assertTrue(fixture.dataRows().all { it.qualityErrorCode == null })
    }

    @Test
    fun `confirm rejects channel target and nonexistent source header`() {
        val fixture = Fixture()
        val upload = fixture.service.upload(csv("id,description\nJ-11,测试内容"), "JIRA", null)

        val crossChannel = assertThrows(ClientException::class.java) {
            fixture.service.confirm(upload.batchId, mapOf(
                "source_record_id" to "id", "description" to "description", "repair_region" to "id"
            ))
        }
        assertEquals("CHANNEL_MAPPING_TARGET_FORBIDDEN",crossChannel.reasonCode)

        val missingHeader = assertThrows(ClientException::class.java) {
            fixture.service.confirm(upload.batchId, mapOf(
                "source_record_id" to "missing", "description" to "description"
            ))
        }
        assertEquals("MAPPING_SOURCE_HEADER_NOT_FOUND",missingHeader.reasonCode)
    }

    @Test
    fun `same filename with different content links previous batch and assigns row slice`() {
        val fixture = Fixture()
        val previous = fixture.newBatch(LocalDateTime.now().minusHours(1))
        every { fixture.batches.findFirstBySourceChannelAndOriginalFilenameOrderByCreatedAtDesc("JIRA", "input.csv") } returns previous

        fixture.service.upload(csv("id,description\nJ-12,新内容"), "JIRA", null)

        val current = fixture.savedBatches.last()
        assertEquals(previous.id, current.previousBatchId)
        assertEquals(0, fixture.dataRows().single().sliceNo)
        assertNotNull(current.nonemptyHeadersJson)
    }

    private fun csv(value: String) = MockMultipartFile("file", "input.csv", "text/csv", value.toByteArray())

    private class Fixture(
        private val nerMasker: NerMasker = NoopNerMasker,
        private val jiraOwnerInitializer: JiraAssigneeOwnerInitializer? = null
    ) {
        val batches = mockk<ImportBatchRepository>()
        val rows = mockk<ImportRowRepository>()
        val sources = mockk<FeedbackSourceRepository>()
        val contents = mockk<FeedbackContentRepository>(relaxed = true)
        val identities = mockk<FeedbackIdentityRepository>(relaxed = true)
        val sdk = mockk<ChannelSdkRepository>(relaxed = true)
        val jira = mockk<ChannelJiraRepository>(relaxed = true)
        val quality = mockk<ChannelQualityRepository>(relaxed = true)
        val redblack = mockk<ChannelRedblackRepository>(relaxed = true)
        val audit = mockk<MaskingAuditRepository>(relaxed = true)
        val savedBatches = mutableListOf<ImportBatch>()
        val savedRows = mutableListOf<ImportRow>()
        val savedSources = mutableListOf<FeedbackSource>()
        val savedQuality = mutableListOf<ChannelQuality>()
        var exactDuplicate: ImportBatch? = null
        var existingSource: FeedbackSource? = null
        var existingContent: FeedbackContent? = null
        var existingJira: ChannelJira? = null
        var previousRow: ImportRow? = null
        var previousQuarantinedRow: ImportRow? = null
        private val batchIds = AtomicLong(1)
        private val rowIds = AtomicLong(1)
        private val sourceIds = AtomicLong(100)
        private val mapper = jacksonObjectMapper()
        private val envelope = EnvelopeCrypto()
        val templateContracts = mockk<ImportTemplateService>()
        val rollbackPersistence = mockk<ImportRollbackRepository>(relaxed = true)
        val service: ImportBatchService

        init {
            every { batches.findFirstBySourceChannelAndContentSha256(any(), any()) } answers { exactDuplicate }
            every { batches.findFirstBySourceChannelAndNormalizedContentHash(any(), any()) } returns null
            every { batches.findFirstBySourceChannelAndOriginalFilenameOrderByCreatedAtDesc(any(), any()) } returns null
            every { batches.save(any()) } answers {
                val input = firstArg<ImportBatch>()
                val saved = if (input.id == 0L) input.copy(id = batchIds.getAndIncrement()) else input
                savedBatches.removeIf { it.id == saved.id }
                savedBatches += saved
                saved
            }
            every { batches.findByBatchId(any()) } answers { savedBatches.find { it.batchId == firstArg() } }
            every { batches.findTop10ByOrderByCreatedAtDesc() } answers { savedBatches.reversed() }
            every { rows.save(any()) } answers {
                val input = firstArg<ImportRow>()
                val saved = if (input.id == 0L) input.copy(id = rowIds.getAndIncrement()) else input
                savedRows.removeIf { it.id == saved.id }
                savedRows += saved
                saved
            }
            every { rows.flush() } returns Unit
            every { rows.findByBatchIdAndRowStatusIn(any(), any()) } answers {
                val batchId = firstArg<Long>(); val statuses = secondArg<Collection<String>>()
                savedRows.filter { it.batchId == batchId && it.rowStatus in statuses }
            }
            every { rows.findTop500ByBatchIdAndRowStatusOrderById(any(), any()) } answers {
                val batchId=firstArg<Long>();val status=secondArg<String>()
                savedRows.filter{it.batchId==batchId&&it.rowStatus==status}.sortedBy{it.id}.take(500)
            }
            every { rows.countByBatchIdAndRecordTypeAndRowStatusIn(any(),any(),any()) } answers {
                val batchId=firstArg<Long>();val type=secondArg<String>();val statuses=thirdArg<Collection<String>>()
                savedRows.count{it.batchId==batchId&&it.recordType==type&&it.rowStatus in statuses}.toLong()
            }
            every { rows.countByBatchIdAndRecordTypeAndRowStatus(any(),any(),any()) } answers {
                val batchId=firstArg<Long>();val type=secondArg<String>();val status=thirdArg<String>()
                savedRows.count{it.batchId==batchId&&it.recordType==type&&it.rowStatus==status}.toLong()
            }
            every { rows.resetRetryableRows(any()) } answers {
                val batchId=firstArg<Long>();var changed=0
                savedRows.filter{it.batchId==batchId&&it.recordType=="ROW"&&it.rowStatus in setOf("FAILED","INVALID","REVIEW_NEEDED")}.forEach{it.rowStatus="PENDING";changed++};changed
            }
            every { rows.resetRowsByStatuses(any(),any()) } answers {
                val batchId=firstArg<Long>();val statuses=secondArg<Collection<String>>();var changed=0
                savedRows.filter{it.batchId==batchId&&it.recordType=="ROW"&&it.rowStatus in statuses}.forEach{it.rowStatus="PENDING";changed++};changed
            }
            every { rows.findByBatchId(any()) } answers { savedRows.filter { it.batchId == firstArg<Long>() } }
            every { rows.findFirstByFeedbackSourceIdOrderByCreatedAtDesc(any()) } answers { previousRow }
            every { rows.findLatestActiveReviewByBusinessKey(any(),any(),any()) } answers { previousQuarantinedRow }
            every { sources.findBySourceChannelAndSourceRecordId(any(), any()) } answers { existingSource }
            every { sources.save(any()) } answers {
                val source = firstArg<FeedbackSource>()
                val saved = if (source.id == 0L) source.copy(id = sourceIds.getAndIncrement()) else source
                savedSources.removeIf { it.id == saved.id }
                savedSources += saved
                saved
            }
            every { sources.findByImportBatchId(any()) } answers { savedSources.filter { it.importBatchId == firstArg<Long>() } }
            every { contents.save(any()) } answers { firstArg() }
            every { contents.findById(any()) } answers { Optional.ofNullable(existingContent) }
            every { identities.findBySourceIdAndIdentityTypeAndIdentityScope(any(), any(), any()) } returns null
            every { identities.save(any()) } answers { firstArg() }
            every { jira.save(any()) } answers { firstArg() }
            every { jira.findById(any()) } answers { Optional.ofNullable(existingJira) }
            every { quality.save(any()) } answers { firstArg<ChannelQuality>().also { savedQuality += it } }
            every { redblack.save(any()) } answers { firstArg() }
            every { audit.save(any()) } answers { firstArg() }
            every { templateContracts.active(any()) } answers {
                ImportTemplate(
                    id = 10L + listOf("JIRA", "QUALITY", "REDBLACK").indexOf(firstArg()),
                    templateName = "test", sourceChannel = firstArg(), version = "v1.0.0",
                    schemaVersion = "import-contract-v1", headerColumns = "[]", fieldMappings = "{}"
                )
            }
            every { templateContracts.view(any()) } answers {
                val template = firstArg<ImportTemplate>()
                ImportTemplateView(template.id, template.sourceChannel, template.templateName, template.version,
                    template.schemaVersion, emptyList(), emptyMap())
            }
            every { templateContracts.byId(any()) } answers {
                val id = firstArg<Long>()
                val channel = listOf("JIRA", "QUALITY", "REDBLACK")[(id - 10).toInt()]
                ImportTemplate(id = id, templateName = "test", sourceChannel = channel, version = "v1.0.0",
                    schemaVersion = "import-contract-v1", headerColumns = "[]", fieldMappings = "{}")
            }
            every { templateContracts.publishNext(any(), any(), any(), any()) } answers {
                val channel = firstArg<String>()
                val id = 10L + listOf("JIRA", "QUALITY", "REDBLACK").indexOf(channel)
                ImportTemplate(id = id, templateName = "test", sourceChannel = channel, version = "v1.0.1",
                    schemaVersion = "import-contract-v1", headerColumns = "[]", fieldMappings = "{}")
            }
            every { templateContracts.boundary(any(), any()) } answers {
                ChannelBoundaryResult(firstArg(), firstArg(), emptyMap(), true, null)
            }
            every { templateContracts.allowedTargets(any()) } answers {
                ImportTemplateService.COMMON_TARGETS + ImportTemplateService.CHANNEL_TARGETS.getValue(firstArg())
            }

            service = ImportBatchService(
                ImportFileParser(), ImportRawCrypto("import-secret-that-is-longer-than-32-characters", envelope, "test-key"),
                MaskingPipeline(audit, mapper, nerMasker),
                IdentityFingerprintService("fingerprint-secret-that-is-longer-than-32-chars", "fp-test"),
                batches, rows, sources, contents, identities, sdk, jira, quality, redblack, EnumMappingRegistry(), mapper,
                templateContracts,
                contactStorage = ContactStorageCrypto(
                    "contact-secret-that-is-longer-than-32-characters", envelope, "contact-test"
                ),
                rollbackPersistence = rollbackPersistence,
                jiraOwnerInitializer = jiraOwnerInitializer
            )
        }

        fun dataRows() = savedRows.filter { it.recordType == "ROW" }

        fun newBatch(createdAt: LocalDateTime): ImportBatch = batches.save(ImportBatch(
            publicId = ByteArray(16), batchId = "old-batch", sourceChannel = "JIRA", originalFilename = "old.csv",
            contentSha256 = ByteArray(32), normalizedContentHash = ByteArray(32), createdAt = createdAt
        ))
    }
}
