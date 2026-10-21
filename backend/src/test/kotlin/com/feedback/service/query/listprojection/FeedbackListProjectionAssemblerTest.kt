package com.feedback.service.query.listprojection

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.mapper.feedback.listprojection.*
import com.feedback.model.po.feedback.listprojection.*
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionAuxiliaryFacts
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.problem.analysis.ClusterFactsPo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackListProjectionAssemblerTest {
    private val assembler = FeedbackListProjectionAssembler(jacksonObjectMapper())
    private val now = LocalDateTime.of(2026, 8, 29, 10, 30)

    @Test
    fun `eligibility fails closed before projection assembly`() {
        val source = source()
        val content = content()
        assertTrue(assembler.isSafelyEligible(source, content))
        assertFalse(assembler.isSafelyEligible(source.copy(dataEnvironment = "TEST"), content))
        assertFalse(assembler.isSafelyEligible(source.copy(sourceChannel = "UNKNOWN"), content))
        assertFalse(assembler.isSafelyEligible(source, content.copy(maskingStatus = "PENDING")))
        assertFalse(assembler.isSafelyEligible(source, content.copy(deleted = true)))
        assertFalse(assembler.isSafelyEligible(source, null))
        assertThrows(ServiceException::class.java) {
            assembler.assemble(7, source, content.copy(maskingStatus = "FAIL"), emptyAuxiliary())
        }
    }

    @Test
    fun `assembler preserves compatibility facts and emits scoped cluster member`() {
        val source = source().copy(importBatchId = 31, faultOccurredAt = now.minusHours(2))
        val content = content().copy(processingStatus = "IN_PROGRESS", updatedAt = now.plusMinutes(1))
        val sdk = FeedbackListSdkFactsPo(sourceId = 11, appId = "learning", packageName = "cn.app", osVersion = "14")
        val auxiliary = emptyAuxiliary().copy(
            assignmentBySourceId = mapOf(11L to FeedbackListAssignmentFactsPo(11, 9, now)),
            handoffBySourceId = mapOf(11L to FeedbackListHandoffFactsPo(11, true, now)),
            attachmentBySourceId = mapOf(11L to FeedbackListAttachmentFactsPo(11, 4, 2, 1, 1, now)),
            importBatchById = mapOf(31L to FeedbackListImportBatchFactsPo(31, "BATCH-31", now)),
            clusterLinkBySourceId = mapOf(11L to FeedbackListClusterLinkFactsPo(11, 88, now)),
            clusterById = mapOf(88L to ClusterFactsPo(id = 88, status = "ACTIVE")),
            identityTypesBySourceId = mapOf(11L to listOf(FeedbackListIdentityTypeFactsPo(11, "DEVICE_SN"))),
            channelBySourceId = mapOf(11L to sdk)
        )
        val snapshot = assembler.assemble(7, source, content, auxiliary)
        with(snapshot.projection) {
            assertEquals("PROCESSING", processingStatus)
            assertEquals("ACCEPTED", distributionStatus)
            assertEquals(9, ownerUserId)
            assertTrue(handoffPending)
            assertEquals(4, attachmentCount)
            assertEquals("BATCH-31", importBatchCode)
            assertEquals(now.minusHours(2), reportedAt)
            assertEquals("learning", channelAppId)
            assertTrue(channelMetaJson!!.contains("learning"))
            assertTrue(channelMetaJson!!.contains("\"app_id\""))
            assertFalse(channelMetaJson!!.contains("source_id"))
            assertFalse(channelMetaJson!!.contains("updated_at"))
            assertEquals("[\"APPLICATION_LOG\",\"DEVICE_SN\"]", diagnosticTypes)
        }
        assertEquals(88, snapshot.clusterMember?.clusterId)
        assertEquals("LEARNING_MACHINE", snapshot.clusterMember?.logicalChannel)
        assertEquals("MODULE_A", snapshot.clusterMember?.functionModule)
    }

    @Test
    fun `channel metadata whitelist excludes diagnostic and free text sentinels`() {
        val sentinel = "DO_NOT_PROJECT_SENSITIVE_TEXT"
        val cases = listOf(
            "SDK" to FeedbackListSdkFactsPo(
                sourceId = 11, appId = "safe-sdk", pageRoute = "safe-route", businessId = "safe-business",
                courseId = "safe-course", questionId = "safe-question", orderId = "safe-order",
                crashStack = sentinel, apiRequestSummary = sentinel,
                stateMachine = sentinel, customEvents = sentinel
            ),
            "JIRA" to FeedbackListJiraFactsPo(sourceId = 11, jiraKey = "safe-jira", commentSummary = sentinel),
            "QUALITY" to FeedbackListQualityFactsPo(
                sourceId = 11, repairRegion = "safe-region", returnReason = sentinel,
                faultConfirmed = sentinel, faultRemark = sentinel
            ),
            "REDBLACK" to FeedbackListRedblackFactsPo(
                sourceId = 11, sourcePlatform = "safe-platform", headline = sentinel
            ),
            "STATION_FEEDBACK" to FeedbackListStationFactsPo(
                sourceId = 11, productId = "safe-product", sourceDescription = sentinel
            )
        )
        val forbiddenKeys = setOf(
            "crash_stack", "api_request_summary", "state_machine", "custom_events", "comment_summary",
            "return_reason", "fault_confirmed", "fault_remark", "headline", "source_description"
        )

        cases.forEach { (channel, facts) ->
            val json = assembler.assemble(
                7,
                source().copy(sourceChannel = channel),
                content(),
                emptyAuxiliary().copy(channelBySourceId = mapOf(11L to facts))
            ).projection.channelMetaJson.orEmpty()
            assertFalse(json.contains(sentinel), channel)
            forbiddenKeys.forEach { key -> assertFalse(json.contains("\"$key\""), "$channel:$key") }
            assertTrue(json.contains("safe-"), channel)
            val keys = jacksonObjectMapper().readTree(json).fieldNames().asSequence().toSet()
            assertEquals(expectedChannelMetaKeys.getValue(channel), keys, channel)
        }
    }

    private val expectedChannelMetaKeys = mapOf(
        "SDK" to setOf(
            "client_feedback_id", "app_id", "package_name", "os_version", "network_type", "device_model",
            "screen_orientation", "input_methods", "trigger_type", "battery_level", "storage_available_mb",
            "integration_mode", "context_source", "page_route", "business_id", "course_id", "question_id", "order_id"
        ),
        "JIRA" to setOf(
            "jira_key", "priority_raw", "jira_status_raw", "project_key", "project_name", "resolution", "assignee",
            "jira_created_at", "jira_updated_at", "resolved_at", "affected_version", "fix_version", "module_raw",
            "due_date", "label_1", "label_2", "label_3"
        ),
        "QUALITY" to setOf(
            "return_order_no", "product_name", "product_model", "dispatch_date", "activation_date", "repair_region",
            "province", "city", "county", "district", "application_type_raw", "exchange_request",
            "merchant_return_apply_date", "actual_shipping_date"
        ),
        "REDBLACK" to setOf(
            "original_id_raw", "author", "source_platform", "pub_type_name", "list_type", "section_raw", "folder_name",
            "keyword", "emotion_type_raw", "evaluation_object", "evaluation_dimension", "impact_level_raw", "data_period",
            "source_department_masked", "source_role_masked", "push_time", "pub_time", "create_time", "update_time",
            "is_deleted_src"
        ),
        "STATION_FEEDBACK" to setOf(
            "source_api_version", "product_id", "source_product_name", "source_app_id", "source_app_name",
            "source_platform", "source_feedback_type", "source_sub_channel", "attribute_schema_version",
            "attribute_mapping_status", "contact_status", "ingest_status", "processing_stage", "retry_count",
            "processed_at", "created_at"
        )
    )

    private fun source() = FeedbackListSourceFactsPo(
        id = 11, sourceChannel = "SDK", sourceRecordId = "SDK-11", sourceEventTime = now.minusHours(3),
        ingestTime = now, dataEnvironment = "PRODUCTION", updatedAt = now
    )
    private fun content() = FeedbackListContentFactsPo(
        sourceId = 11, title = "脱敏标题", description = "脱敏正文", functionModule = "MODULE_A",
        processingStatus = "OPEN", maskingStatus = "PASS", deleted = false, updatedAt = now
    )
    private fun emptyAuxiliary() = FeedbackListProjectionAuxiliaryFacts(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
    )
}
