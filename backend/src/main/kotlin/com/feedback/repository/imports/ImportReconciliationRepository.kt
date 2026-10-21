package com.feedback.repository.imports

import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import org.springframework.stereotype.Repository

data class ImportReconciliationRecord(
    val sourceChannel: String, val sourceRecordId: String?, val title: String?, val description: String?,
    val assignee: String?, val maskingStatus: String?, val processingStatus: String?, val rowStatus: String?,
    val failCode: String?, val failReason: String?, val attachmentCount: Int, val availableAttachmentCount: Int
)
data class ImportReconciliationData(val batch: ImportReconciliationBatchPo, val records: List<ImportReconciliationRecord>)

@Repository
class ImportReconciliationRepository(
    private val batches: ImportReconciliationBatchMapper,
    private val rows: ImportReconciliationRowMapper,
    private val sources: ImportReconciliationSourceMapper,
    private val contents: ImportReconciliationContentMapper,
    private val jira: ImportReconciliationJiraMapper
) {
    fun load(batchId: String): ImportReconciliationData {
        val batch = batches.findByBatchId(batchId) ?: importRepositoryNotFound("IMPORT_BATCH_NOT_FOUND")
        val importRows = rows.findRows(batch.id)
        val sourceIds = importRows.mapNotNull { it.feedbackSourceId }.distinct()
        val sourceById = sourceIds.chunked(200).flatMap(sources::findByIds).associateBy { it.id }
        val contentBySource = sourceIds.chunked(200).flatMap(contents::findPassingBySourceIds).associateBy { it.sourceId }
        val jiraBySource = sourceIds.chunked(200).flatMap(jira::findBySourceIds).associateBy { it.sourceId }
        val attachments = importRows.map { it.id }.chunked(200).flatMap(rows::findAttachmentStats).associateBy { it.parentImportRowId }
        val records = importRows.map { row ->
            val source = row.feedbackSourceId?.let(sourceById::get)
            val content = row.feedbackSourceId?.let(contentBySource::get)
            val jiraRow = row.feedbackSourceId?.let(jiraBySource::get)
            val attachment = attachments[row.id]
            ImportReconciliationRecord(
                batch.sourceChannel, source?.sourceRecordId, content?.title, content?.description, jiraRow?.assignee,
                content?.maskingStatus, content?.processingStatus, row.rowStatus, row.failCode, row.failReason,
                attachment?.attachmentCount ?: 0, attachment?.availableAttachmentCount ?: 0
            )
        }
        return ImportReconciliationData(batch, records)
    }
}
