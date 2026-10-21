package com.feedback.repository.feedback.diagnostics

import com.feedback.application.port.feedback.diagnostics.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.feedback.diagnostics.*
import com.feedback.model.po.feedback.diagnostics.*
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

/** 诊断查看的单表事实边界；集合读取使用第 201 行作为超限哨兵并失败关闭。 */
@Repository
class FeedbackDiagnosticsRepository(
    private val identityMapper: FeedbackDiagnosticIdentityMapper,
    private val stationMapper: FeedbackDiagnosticStationMapper,
    private val sdkMapper: FeedbackDiagnosticSdkMapper,
    private val attachmentMapper: FeedbackDiagnosticAttachmentMapper,
    private val auditMapper: FeedbackDiagnosticAuditMapper
) {
    fun identities(sourceId: Long) = bounded(identityMapper.findAuthorized(sourceId, SENTINEL), "IDENTITY")
    fun attachments(sourceId: Long) = bounded(attachmentMapper.findLogs(sourceId, SENTINEL), "ATTACHMENT")
    fun station(sourceId: Long) = single(stationMapper.findBySourceId(sourceId, SENTINEL), "STATION")
    fun sdk(sourceId: Long) = single(sdkMapper.findBySourceId(sourceId, SENTINEL), "SDK")

    /** 当前页批量读取；调用方已完成列表数据范围校验，结果仍按单反馈哨兵逐组失败关闭。 */
    fun identitiesBySourceIds(sourceIds: List<Long>): Map<Long, List<FeedbackDiagnosticIdentityPo>> {
        validateSourceIds(sourceIds)
        return grouped(identityMapper.findAuthorizedBySourceIds(sourceIds, SENTINEL), { it.sourceId }, "IDENTITY")
    }

    fun stationsBySourceIds(sourceIds: List<Long>): Map<Long, FeedbackDiagnosticStationPo> {
        validateSourceIds(sourceIds)
        return groupedSingles(stationMapper.findBySourceIds(sourceIds, SINGLE_SENTINEL), { it.sourceId }, "STATION")
    }

    fun sdksBySourceIds(sourceIds: List<Long>): Map<Long, FeedbackDiagnosticSdkPo> {
        validateSourceIds(sourceIds)
        return groupedSingles(sdkMapper.findBySourceIds(sourceIds, SINGLE_SENTINEL), { it.sourceId }, "SDK")
    }

    fun appendAudit(userId: Long, sourceId: Long, detailJson: String, ipAddress: String?) {
        if (auditMapper.insertViewAudit(userId, sourceId.toString(), detailJson, ipAddress) != 1) {
            writeFailed()
        }
    }

    fun appendAudits(rows: List<FeedbackDiagnosticAuditPo>) {
        if (rows.isEmpty()) return
        if (rows.size > 100) invalid("FEEDBACK_DIAGNOSTICS_AUDIT_BATCH_INVALID")
        if (auditMapper.insertViewAudits(rows) != rows.size) writeFailed()
    }

    private fun <T> bounded(rows: List<T>, type: String): List<T> {
        if (rows.size >= SENTINEL) readUnavailable("FEEDBACK_DIAGNOSTICS_${type}_LIMIT_EXCEEDED")
        return rows
    }
    private fun <T> single(rows: List<T>, type: String): T? {
        if (rows.size > 1) readUnavailable("FEEDBACK_DIAGNOSTICS_${type}_DUPLICATE")
        return rows.singleOrNull()
    }
    private fun <T> grouped(
        rows: List<T>, sourceId: (T) -> Long, type: String
    ): Map<Long, List<T>> {
        return rows.groupBy(sourceId).onEach { (_, values) -> bounded(values, type) }
    }

    private fun <T> groupedSingles(
        rows: List<T>, sourceId: (T) -> Long, type: String
    ): Map<Long, T> = grouped(rows, sourceId, type).mapValues { (_, values) ->
        if (values.size > 1) readUnavailable("FEEDBACK_DIAGNOSTICS_${type}_DUPLICATE")
        values.single()
    }

    private fun validateSourceIds(sourceIds: List<Long>) {
        if (sourceIds.isEmpty() || sourceIds.size > 100 || sourceIds.any { it <= 0 }) {
            invalid("FEEDBACK_DIAGNOSTICS_BATCH_INVALID")
        }
    }

    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

    private fun readUnavailable(reasonCode: String): Nothing = throw ServiceException(
        ErrorCode.FEEDBACK_READ_UNAVAILABLE,
        reasonCode
    )

    private fun writeFailed(): Nothing = throw ServiceException(
        ErrorCode.INTERNAL_ERROR,
        "FEEDBACK_DIAGNOSTICS_AUDIT_WRITE_FAILED"
    )

    private companion object { const val SENTINEL = 201; const val SINGLE_SENTINEL = 2 }
}

/**
 * 诊断应用端口的持久化适配器。Repository 的 PO、审计 JSON 和批量哨兵都止于此处，
 * 上层只接触具名诊断事实与审计事件。
 */
@Component
class FeedbackDiagnosticsPersistenceAdapter(
    private val repository: FeedbackDiagnosticsRepository,
    private val objectMapper: ObjectMapper
) : FeedbackDiagnosticsReadPort, FeedbackDiagnosticsAuditPort {
    override fun identities(sourceId: Long) = repository.identities(sourceId).map { it.toFact() }

    override fun attachments(sourceId: Long) = repository.attachments(sourceId).map { it.toFact() }

    override fun station(sourceId: Long) = repository.station(sourceId)?.toFact()

    override fun sdk(sourceId: Long) = repository.sdk(sourceId)?.toFact()

    override fun identitiesBySourceIds(sourceIds: List<Long>) = repository.identitiesBySourceIds(sourceIds)
        .mapValues { (_, rows) -> rows.map { it.toFact() } }

    override fun stationsBySourceIds(sourceIds: List<Long>) = repository.stationsBySourceIds(sourceIds)
        .mapValues { (_, row) -> row.toFact() }

    override fun sdksBySourceIds(sourceIds: List<Long>) = repository.sdksBySourceIds(sourceIds)
        .mapValues { (_, row) -> row.toFact() }

    override fun append(event: FeedbackDiagnosticsAuditEvent) {
        repository.appendAudit(event.actorId, event.sourceId, event.detailJson(), event.ipAddress)
    }

    override fun appendAll(events: List<FeedbackDiagnosticsAuditEvent>) {
        repository.appendAudits(events.map { event ->
            FeedbackDiagnosticAuditPo(
                event.actorId,
                event.sourceId.toString(),
                event.detailJson(),
                event.ipAddress
            )
        })
    }

    private fun FeedbackDiagnosticsAuditEvent.detailJson() = objectMapper.writeValueAsString(
        mapOf(
            "purposeCode" to purposeCode,
            "purposeSha256" to purposeSha256,
            "purposeLength" to purposeLength,
            "types" to types,
            "visibility" to visibility
        )
    )

    private fun FeedbackDiagnosticIdentityPo.toFact() = FeedbackDiagnosticIdentityFact(
        sourceId, identityType, contactCiphertext, contactNonce, contactTag, contactKeyId, contactAlgorithm
    )

    private fun FeedbackDiagnosticStationPo.toFact() = FeedbackDiagnosticStationFact(
        sourceId, sourceUserId, authenticatedUserId
    )

    private fun FeedbackDiagnosticSdkPo.toFact() = FeedbackDiagnosticSdkFact(
        sourceId, diagnosticCiphertext, diagnosticNonce, diagnosticTag, diagnosticKeyId, diagnosticAlgorithm
    )

    private fun FeedbackDiagnosticAttachmentPo.toFact() = FeedbackDiagnosticAttachmentFact(
        id, sourceAttachmentId, mimeType, fileSizeBytes, securityStatus, integrityStatus,
        malwareScanStatus, contentReviewStatus
    )
}
