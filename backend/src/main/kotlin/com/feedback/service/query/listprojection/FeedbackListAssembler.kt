package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListRecord
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.query.detail.FeedbackChannelMetaResponse
import com.feedback.service.query.detail.FeedbackDiagnosticAvailabilityResponse
import com.feedback.service.query.detail.JiraFeedbackChannelMetaResponse
import com.feedback.service.query.detail.QualityFeedbackChannelMetaResponse
import com.feedback.service.query.detail.RedblackFeedbackChannelMetaResponse
import com.feedback.service.query.detail.SdkFeedbackChannelMetaResponse
import com.feedback.service.query.detail.StationFeedbackChannelMetaResponse
import com.feedback.service.query.FeedbackDiagnosticItemResponse
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** 将单行安全投影组装为具名公开响应；不访问数据库，也不推断当前用户权限。 */
@Component
class FeedbackListAssembler(private val objectMapper: ObjectMapper) {
    fun row(
        projection: FeedbackListRecord,
        ownerName: String?,
        policy: DataFieldPolicy,
        keyDiagnostics: List<FeedbackDiagnosticItemResponse> = emptyList(),
        diagnosticsVisibility: String = "PARTIALLY_HIDDEN"
    ): FeedbackListRowResponse {
        val metaNode = projection.channelMetaJson?.let(objectMapper::readTree)
        val diagnosticTypes = projection.diagnosticTypes?.let(objectMapper::readTree)
            ?.takeIf(JsonNode::isArray)?.mapNotNull { it.textValue() }?.distinct()?.sorted().orEmpty()
        val channelMeta = channelMeta(projection.sourceChannel, metaNode, policy)
        val publishedAt = (channelMeta as? RedblackFeedbackChannelMetaResponse)?.pubTime
        return FeedbackListRowResponse(
            id = projection.sourceId,
            sourceChannel = projection.sourceChannel.lowercase(),
            sourceRecordId = projection.sourceRecordId,
            title = projection.title?.takeIf(String::isNotBlank)
                ?: projection.description?.takeIf(String::isNotBlank)
                ?: "反馈 #${projection.sourceId}",
            description = projection.description,
            module = projection.functionModule?.takeIf(String::isNotBlank) ?: "UNKNOWN",
            problemType = projection.problemType,
            issueCategory = projection.issueCategory?.takeIf(String::isNotBlank) ?: "UNKNOWN",
            severity = projection.severityLevel ?: projection.aiSeverity,
            severityProvisional = projection.severityLevel == null && projection.aiSeverity != null,
            severityReviewStatus = projection.aiReviewStatus,
            emotion = projection.userSentiment,
            status = projection.processingStatus,
            distributionStatus = projection.distributionStatus,
            reportedAt = projection.reportedAt,
            sourceUpdatedAt = projection.sourceUpdateTime,
            platformUpdatedAt = projection.platformUpdateTime,
            ingestTime = projection.ingestTime,
            appVersion = projection.appVersion,
            province = projection.provinceCode,
            city = projection.cityCode,
            ownerId = projection.ownerUserId,
            ownerName = ownerName,
            attachmentCount = projection.attachmentCount,
            screenshotCount = projection.screenshotCount,
            logCount = projection.logCount,
            videoCount = projection.videoCount,
            channelMeta = channelMeta,
            diagnosticAvailability = FeedbackDiagnosticAvailabilityResponse(
                diagnosticTypes.isNotEmpty(), diagnosticTypes, false, policy
            ),
            keyDiagnostics = keyDiagnostics,
            diagnosticsVisibility = diagnosticsVisibility,
            learningMachineAppName = when (projection.sourceChannel) {
                "SDK" -> projection.channelAppId ?: projection.channelPackageName ?: projection.packageName
                "STATION_FEEDBACK" -> metaNode.text("source_app_name")
                    ?: metaNode.text("source_product_name") ?: projection.stationAppId
                else -> null
            },
            publishedAt = publishedAt,
            sdkDataType = if (projection.sourceChannel == "SDK") {
                if (projection.importBatchId == null) "REAL_SDK" else "SIMULATED_IMPORT"
            } else null,
            sentimentConflict = sentimentConflict(projection),
            fieldPolicy = policy
        )
    }

    private fun channelMeta(channel: String, node: JsonNode?, policy: DataFieldPolicy): FeedbackChannelMetaResponse? =
        if (node == null || !node.isObject) null else when (channel) {
            "SDK" -> SdkFeedbackChannelMetaResponse(
                node.text("client_feedback_id"), node.text("app_id"), node.text("package_name"), node.text("os_version"),
                node.text("network_type"), node.text("device_model"), node.text("screen_orientation"),
                node.text("input_methods"), node.text("trigger_type"), node.decimal("battery_level"),
                node.long("storage_available_mb"), node.text("integration_mode"), node.text("context_source"),
                node.text("page_route"), node.text("business_id"), null, node.text("course_id"),
                node.text("question_id"), node.text("order_id"), null, null, null, policy
            )
            "JIRA" -> JiraFeedbackChannelMetaResponse(
                node.text("jira_key"), node.text("priority_raw"), node.text("jira_status_raw"), node.text("project_key"),
                node.text("project_name"), node.text("resolution"), node.text("assignee"),
                node.dateTime("jira_created_at"), node.dateTime("jira_updated_at"), node.dateTime("resolved_at"),
                node.text("affected_version"), node.text("fix_version"), node.text("module_raw"), node.date("due_date"),
                node.text("label_1"), node.text("label_2"), node.text("label_3"), null, policy
            )
            "QUALITY" -> QualityFeedbackChannelMetaResponse(
                node.text("return_order_no"), node.text("product_name"), node.text("product_model"),
                node.date("dispatch_date"), node.date("activation_date"), node.text("repair_region"),
                node.text("province"), node.text("city"), node.text("county"), node.text("district"),
                node.text("application_type_raw"), node.boolean("exchange_request"), null, null, null,
                node.dateTime("merchant_return_apply_date"), node.date("actual_shipping_date"), policy
            )
            "REDBLACK" -> RedblackFeedbackChannelMetaResponse(
                node.text("original_id_raw"), null, node.text("author"), node.text("source_platform"),
                node.text("pub_type_name"), node.text("list_type"), node.text("section_raw"), node.text("folder_name"),
                node.text("keyword"), node.text("emotion_type_raw"), node.text("evaluation_object"),
                node.text("evaluation_dimension"), node.text("impact_level_raw"), node.text("data_period"),
                node.text("source_department_masked"), node.text("source_role_masked"), node.dateTime("push_time"),
                node.dateTime("pub_time"), node.dateTime("create_time"), node.dateTime("update_time"),
                node.boolean("is_deleted_src"), policy
            )
            "STATION_FEEDBACK" -> StationFeedbackChannelMetaResponse(
                node.text("source_api_version"), node.text("product_id"), node.text("source_product_name"),
                node.text("source_app_id"), node.text("source_app_name"), node.text("source_platform"),
                node.text("source_feedback_type"), node.text("source_sub_channel"), null,
                node.text("attribute_schema_version"), node.text("attribute_mapping_status"), node.text("contact_status"),
                node.text("ingest_status"), node.text("processing_stage"), node.int("retry_count"),
                node.dateTime("processed_at"), node.dateTime("created_at"), policy
            )
            else -> null
        }

    private fun sentimentConflict(row: FeedbackListRecord): Boolean {
        if (row.sourceChannel != "REDBLACK") return false
        val title = row.title.orEmpty()
        return (row.userSentiment == "NEGATIVE" && ("强烈推荐" in title || "很好用" in title)) ||
            (row.userSentiment == "POSITIVE" && ("太差" in title || "垃圾" in title))
    }

    private fun JsonNode?.value(name: String): JsonNode? = this?.get(name)?.takeUnless(JsonNode::isNull)
    private fun JsonNode?.text(name: String): String? = value(name)?.takeIf(JsonNode::isValueNode)?.asText()
        ?.takeIf(String::isNotBlank)
    private fun JsonNode?.long(name: String): Long? = value(name)?.takeIf(JsonNode::isNumber)?.longValue()
    private fun JsonNode?.int(name: String): Int? = value(name)?.takeIf(JsonNode::isNumber)?.intValue()
    private fun JsonNode?.decimal(name: String): BigDecimal? = value(name)?.takeIf(JsonNode::isNumber)?.decimalValue()
    private fun JsonNode?.boolean(name: String): Boolean? = value(name)?.takeIf(JsonNode::isBoolean)?.booleanValue()
    private fun JsonNode?.date(name: String): LocalDate? = text(name)?.let(LocalDate::parse)
    private fun JsonNode?.dateTime(name: String): LocalDateTime? = text(name)?.let(LocalDateTime::parse)
}
