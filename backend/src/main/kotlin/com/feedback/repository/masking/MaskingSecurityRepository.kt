package com.feedback.repository.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.masking.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.repository.ImportBatchRepository
import com.feedback.repository.ImportRowRepository
import org.springframework.stereotype.Repository

data class MaskingTableRawRecord(
    val content: ByteArray, val nonce: ByteArray, val tag: ByteArray, val mappingConfigJson: String
)

@Repository
class MaskingSecurityRepository(
    private val sdk: MaskingSecuritySdkMapper,
    private val sources: MaskingSecuritySourceMapper,
    private val importRows: MaskingSecurityImportRowMapper,
    private val identity: MaskingSecurityIdentityMapper,
    private val contents: MaskingSecurityContentMapper,
    private val station: MaskingSecurityStationMapper,
    private val audits: MaskingSecurityAuditMapper,
    private val rows: ImportRowRepository,
    private val batches: ImportBatchRepository
) {
    fun quarantine(view: String, limit: Int, offset: Long): Pair<List<Map<String, Any?>>, Long> =
        sdk.list(view, limit, offset).map { row -> linkedMapOf<String, Any?>(
            "receiptId" to row.receiptId, "status" to row.status, "bodyStatus" to row.bodyStatus,
            "contactStatus" to row.contactStatus, "urgency" to row.urgency, "retryCount" to row.retryCount,
            "dispositionStatus" to row.dispositionStatus, "dispositionReason" to row.dispositionReason,
            "dispositionNote" to row.dispositionNote, "dispositionAt" to row.dispositionAt,
            "errorCode" to row.errorCode, "retentionUntil" to row.retentionUntil, "nextRetryAt" to row.nextRetryAt,
            "receivedAt" to row.receivedAt, "retryable" to row.retryable, "actionHint" to row.actionHint
        ) } to sdk.count(view)

    fun retrySdk(receipt: ByteArray) = sdk.retry(receipt)
    fun retryImport(publicId: String): Int = sources.findIdByPublicId(publicId)?.let(importRows::retry) ?: 0
    fun closeSdk(receipt: ByteArray, reason: String, note: String, actor: Long) = sdk.close(receipt, reason, note, actor)
    fun sdkRaw(receipt: ByteArray) = sdk.findRaw(receipt)
    fun contacts(sourceId: Long) = identity.findAuthorized(sourceId)

    fun tableRaw(rowId: Long): MaskingTableRawRecord? {
        val row = rows.findById(rowId).orElse(null) ?: return null
        if (row.recordType != "ROW" || row.rowStatus !in setOf("REVIEW_NEEDED", "FAILED") ||
            row.rawRetentionUntil?.isAfter(java.time.LocalDateTime.now()) != true || row.rawDeletedAt != null) return null
        val batch = batches.findById(row.batchId).orElse(null) ?: return null
        return MaskingTableRawRecord(row.rawRowContentEnc ?: failed("MASKING_RAW_CONTENT_MISSING"),
            row.rawNonce ?: failed("MASKING_RAW_NONCE_MISSING"), row.rawTag ?: failed("MASKING_RAW_TAG_MISSING"),
            batch.mappingConfigJson ?: failed("MASKING_MAPPING_CONFIG_MISSING"))
    }

    fun stationReviewable(sourceId: Long): Boolean =
        sources.findChannel(sourceId) == "STATION_FEEDBACK" && contents.isStationReviewable(sourceId) == 1
    fun keepStation(sourceId: Long, reason: String) = station.keep(sourceId, reason)
    fun releaseStation(sourceId: Long, title: String?, description: String?) = contents.release(sourceId, title, description)
    fun completeStation(sourceId: Long) = station.complete(sourceId)
    fun audit(userId: Long, operation: String, outcome: String, target: String, detailJson: String, ip: String?) {
        if(audits.insert(userId, operation, outcome, target, detailJson, ip) != 1) failed("MASKING_SECURITY_AUDIT_INSERT_FAILED")
    }
    private fun failed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}
