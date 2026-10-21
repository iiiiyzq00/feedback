package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListRecord
import com.feedback.security.WebPrincipal
import com.feedback.service.query.FeedbackDiagnosticItemResponse
import com.feedback.service.query.FeedbackDiagnosticValueDecoder
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditEvent
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditPort
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsReadPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class ListDiagnosticValues(
    val items: List<FeedbackDiagnosticItemResponse>,
    val visibility: String
)

data class ListDiagnosticAudit(
    val actorId: Long,
    val sourceId: Long,
    val types: List<String>,
    val visibility: String
)

data class ListDiagnosticReadResult(
    val values: Map<Long, ListDiagnosticValues>,
    val audits: List<ListDiagnosticAudit>
)

/** 当前页关键信息批量读取器。三条有界批量查询替代前端逐行请求，日志正文和附件令牌不进入列表响应。 */
@Component
class ListDiagnosticReader(
    private val reads: FeedbackDiagnosticsReadPort,
    private val decoder: FeedbackDiagnosticValueDecoder
) {
    fun read(rows: List<FeedbackListRecord>, principal: WebPrincipal): ListDiagnosticReadResult {
        if (rows.isEmpty()) return ListDiagnosticReadResult(emptyMap(), emptyList())
        val sourceIds = rows.map { it.sourceId }.distinct()
        val identities = reads.identitiesBySourceIds(sourceIds)
        val stationIds = rows.filter { it.sourceChannel == "STATION_FEEDBACK" }.map { it.sourceId }
        val sdkIds = rows.filter { it.sourceChannel == "SDK" }.map { it.sourceId }
        val stations = if (stationIds.isEmpty()) emptyMap() else reads.stationsBySourceIds(stationIds)
        val sdks = if (sdkIds.isEmpty()) emptyMap() else reads.sdksBySourceIds(sdkIds)
        val visibility = decoder.visibility(principal)
        val result = rows.associate { row ->
            val decoded = decoder.identities(identities[row.sourceId].orEmpty()) +
                (if (row.sourceChannel == "STATION_FEEDBACK") decoder.station(stations[row.sourceId]) else emptyList()) +
                (if (row.sourceChannel == "SDK") decoder.sdk(sdks[row.sourceId]) else emptyList())
            val keyInformation = decoder.distinctSemanticValues(decoded.filter { it.type != "APPLICATION_LOG" })
            row.sourceId to ListDiagnosticValues(decoder.present(keyInformation, principal), visibility)
        }
        val audits = result.filterValues { it.items.isNotEmpty() }.map { (sourceId, value) ->
            ListDiagnosticAudit(
                principal.userId, sourceId, value.items.map { it.type }.distinct(), visibility
            )
        }
        return ListDiagnosticReadResult(result, audits)
    }
}

/** 在列表只读快照事务结束后写诊断查看审计；写入失败时用例不会返回敏感诊断。 */
@Component
class ListDiagnosticAuditWriter(
    private val audits: FeedbackDiagnosticsAuditPort
) {
    @Transactional
    fun append(audits: List<ListDiagnosticAudit>) {
        val digest = com.feedback.audit.AuditTextDigester.digest(DIRECT_LIST_VIEW)
        this.audits.appendAll(audits.map { audit ->
            FeedbackDiagnosticsAuditEvent(
                actorId = audit.actorId,
                sourceId = audit.sourceId,
                purposeCode = "DIRECT_LIST_VIEW",
                purposeSha256 = digest.sha256,
                purposeLength = digest.length,
                types = audit.types,
                visibility = audit.visibility,
                ipAddress = null
            )
        })
    }

    private companion object { const val DIRECT_LIST_VIEW = "反馈列表直接展示" }
}
