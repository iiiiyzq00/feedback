package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListRecord
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.masking.OutboundPrivacyGate
import org.springframework.stereotype.Component

/** 将已授权的安全投影编码为 CSV，并在成功结果返回前写入原有业务审计队列。 */
@Component
class FeedbackCsvExporter(
    private val outboundPrivacyGate: OutboundPrivacyGate,
    private val audits: BusinessAuditService
) {
    fun export(
        rows: List<FeedbackListRecord>,
        policy: DataFieldPolicy,
        actorId: Long,
        generationId: Long,
        mode: String,
        context: FeedbackExportAuditContext
    ): String {
        rows.forEach { row ->
            outboundPrivacyGate.requireSafe("FEEDBACK_EXPORT", row.sourceId, row.title, row.description)
        }
        val columns = listOf(
            CsvColumn("id") { it.sourceId },
            CsvColumn("source_channel") { it.sourceChannel },
            CsvColumn("source_record_id") { it.sourceRecordId },
            CsvColumn("title") { row -> row.title?.takeIf(String::isNotBlank) ?: row.description ?: "反馈 #${row.sourceId}" },
            CsvColumn("description") { it.description },
            CsvColumn("function_module") { it.functionModule?.takeIf(String::isNotBlank) ?: "UNKNOWN" },
            CsvColumn("severity") { it.severityLevel ?: it.aiSeverity },
            CsvColumn("status") { it.processingStatus },
            CsvColumn("reported_at") { it.reportedAt }
        ).filter { policy.permits(it.name) }
        val csv = sequenceOf(columns.joinToString(",", transform = CsvColumn::name))
            .plus(rows.asSequence().map { row -> columns.joinToString(",") { csv(it.value(row)) } })
            .joinToString("\n")
        audits.enqueue(BusinessAuditEvent(
            actor = actorId,
            operation = "FEEDBACK_EXPORT",
            auditClass = "QUERY",
            outcome = "SUCCESS",
            targetType = "FEEDBACK_LIST_GENERATION",
            targetId = generationId.toString(),
            requestId = context.requestId,
            clientIp = context.clientIp,
            detail = mapOf("mode" to mode, "rowCount" to rows.size, "generationId" to generationId)
        ))
        return csv
    }

    private fun csv(value: Any?): String {
        var safe = value?.toString().orEmpty()
        if (safe.firstOrNull() in setOf('=', '+', '-', '@')) safe = "'$safe"
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private data class CsvColumn(val name: String, val value: (FeedbackListRecord) -> Any?)
}
