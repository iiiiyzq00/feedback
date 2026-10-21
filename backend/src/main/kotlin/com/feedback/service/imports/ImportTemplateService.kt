package com.feedback.service.imports

import com.feedback.model.imports.ImportTemplate
import com.feedback.repository.ImportTemplateRepository
import com.feedback.repository.imports.ImportTemplateConfigRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class ImportTemplateView(
    val id: Long,
    val channel: String,
    val name: String,
    val version: String,
    val schemaVersion: String,
    val headers: List<String>,
    val mapping: Map<String, String>
)

data class ChannelBoundaryResult(
    val declaredChannel: String,
    val detectedChannel: String?,
    val matchedSignatures: Map<String, List<String>>,
    val valid: Boolean,
    val reason: String?
)

@Service
class ImportTemplateService(
    private val repository: ImportTemplateRepository,
    private val mapper: ObjectMapper,
    private val configs: ImportTemplateConfigRepository
) {
    fun activeTemplates(): List<ImportTemplateView> =
        configs.active().map(::view)

    fun active(channel: String): ImportTemplate =
        configs.active(channel)
            ?: importFailed("IMPORT_TEMPLATE_NOT_CONFIGURED")

    fun byId(id: Long): ImportTemplate = configs.byLegacyId(id)
        ?: importInvalid("IMPORT_TEMPLATE_NOT_FOUND")

    fun csv(channel: String): Pair<String, ByteArray> {
        val template = active(channel)
        val view = view(template)
        val header = view.headers.joinToString(",") { csvCell(it) }
        val example = view.headers.joinToString(",") { csvCell(EXAMPLES[it].orEmpty()) }
        return "${channel.lowercase()}-${template.version}.csv" to "\uFEFF$header\r\n$example\r\n".toByteArray()
    }

    fun boundary(declaredChannel: String, headers: List<String>): ChannelBoundaryResult {
        val normalized = headers.map(::normalizeHeader).toSet()
        val matches = SIGNATURES.mapValues { (_, signatures) ->
            signatures.filter { normalizeHeader(it) in normalized }
        }
        val scores = matches.mapValues { it.value.size }
        val declaredScore = scores.getValue(declaredChannel)
        val best = scores.maxByOrNull { it.value }
        val detected = best?.takeIf { it.value > 0 }?.key
        val conflict = detected != null && detected != declaredChannel && best!!.value >= 2 && declaredScore == 0
        return ChannelBoundaryResult(
            declaredChannel, detected, matches.filterValues(List<String>::isNotEmpty), !conflict,
            if (conflict) "CHANNEL_HEADER_MISMATCH:declared=$declaredChannel,detected=$detected" else null
        )
    }

    fun allowedTargets(channel: String): Set<String> = COMMON_TARGETS + CHANNEL_TARGETS.getValue(channel)

    fun view(template: ImportTemplate): ImportTemplateView = ImportTemplateView(
        template.id, template.sourceChannel, template.templateName, template.version, template.schemaVersion,
        mapper.readValue(template.headerColumns, object : TypeReference<List<String>>() {}),
        mapper.readValue(template.fieldMappings, object : TypeReference<Map<String, String>>() {})
    )

    @Transactional
    fun publishNext(channel: String, headers: List<String>, mapping: Map<String, String>, actor: Long? = null): ImportTemplate {
        val current = active(channel)
        repository.saveAndFlush(current.copy(isActive = false, updatedAt = LocalDateTime.now()))
        val numbers = Regex("(\\d+)").findAll(current.version).map { it.value.toInt() }.toList()
        val nextVersion = if (numbers.size >= 3) "v${numbers[0]}.${numbers[1]}.${numbers[2] + 1}"
            else "v1.0.${current.id + 1}"
        return repository.save(ImportTemplate(
            templateName = current.templateName,
            sourceChannel = channel,
            version = nextVersion,
            schemaVersion = current.schemaVersion,
            headerColumns = mapper.writeValueAsString(headers),
            fieldMappings = mapper.writeValueAsString(mapping),
            createdBy = actor
        ))
    }

    private fun csvCell(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun normalizeHeader(value: String) = value.trim().lowercase().replace(Regex("[ _-]+"), "")

    companion object {
        val COMMON_TARGETS = setOf(
            "source_record_id", "title", "description", "function_module", "package_name", "app_version", "device_model",
            "source_event_time", "source_update_time", "severity_level", "problem_type", "reproducibility",
            "standard_problem_type", "issue_category", "processing_status", "user_sentiment",
            "province_code", "city_code", "district_code",
            "source_url", "attachment_urls", "attachment_sha256", "attachment_size"
        )
        val CHANNEL_TARGETS = mapOf(
            "SDK" to setOf(
                "app_id", "package_name", "os_version", "network_type", "device_model", "trigger_type", "input_methods",
                "screen_orientation", "app_session_id", "battery_level", "storage_available_mb",
                "app_internal_logs", "crash_stack", "account_context", "course_id", "question_id", "order_id",
                "page_route", "business_id", "api_request_summary", "state_machine", "custom_events", "private_file_manifest"
            ),
            "JIRA" to setOf(
                "jira_issue_type", "jira_issue_id", "status", "priority", "project_key", "project_name",
                "project_type", "project_lead", "project_description", "project_url", "resolution",
                "assignee", "reporter", "creator", "last_viewed_at", "resolved_at", "affected_version",
                "fix_version", "due_date", "votes", "label_1", "label_2", "label_3", "environment",
                "watchers", "worklog_time", "original_estimate_raw", "remaining_estimate_raw",
                "time_spent_raw", "work_ratio", "sigma_original_estimate", "sigma_remaining_estimate",
                "sigma_time_spent", "security_level", "inward_duplicate_link", "outward_duplicate_link", "comment_summary"
            ),
            "QUALITY" to setOf(
                "repair_region", "quality_province", "quality_city", "quality_county", "customer_address",
                "district", "application_type", "exchange_request", "return_reason", "fault_confirmed",
                "shipping_date", "return_order_no", "product_name", "product_model", "device_sn",
                "dispatch_date", "activation_date"
            ),
            "REDBLACK" to setOf(
                "original_id", "uid", "author", "url", "source_platform", "pub_type", "section",
                "folder_name", "origin_message", "keyword", "emotion", "push_time", "redblack_create_time",
                "cover_image_url", "image_urls", "video_urls", "is_deleted", "evaluation_object",
                "evaluation_dimension", "impact_level", "data_period", "source_department", "source_role"
            )
        )
        private val SIGNATURES = mapOf(
            "SDK" to setOf("应用标识", "应用包名", "触发方式", "应用内部日志", "崩溃堆栈", "页面路由"),
            "JIRA" to setOf("issue key", "jira key", "项目key", "assignee", "reporter", "经办人", "报告人"),
            "QUALITY" to setOf("id", "报修区域", "维修区域", "repair region", "故障现象确认", "申请类型", "application type", "商返申请日期"),
            "REDBLACK" to setOf("红黑榜类型", "情感类型", "source platform", "来源平台", "作者", "链接", "uid")
        )
        private val EXAMPLES = mapOf(
            "问题编号" to "DEMO-001", "问题描述" to "模拟反馈内容", "问题标题" to "模拟问题",
            "创建时间" to "2026-07-29T10:00:00", "更新时间" to "2026-07-29T10:30:00",
            "状态" to "Open", "优先级" to "High", "项目Key" to "DEMO",
            "维修区域" to "华东", "申请类型" to "换货", "换货申请" to "是",
            "原始ID" to "10001", "来源平台" to "DEMO", "红黑榜类型" to "黑榜"
            ,"应用标识" to "app-store", "应用包名" to "com.example.app", "触发方式" to "LAUNCHER",
            "应用内部日志" to "模拟日志：页面加载失败", "崩溃堆栈" to "SimulatedException: demo only",
            "页面路由" to "/demo/detail", "私有文件清单" to "仅模拟文件名、大小和类型，不包含文件正文"
        )
    }
}
