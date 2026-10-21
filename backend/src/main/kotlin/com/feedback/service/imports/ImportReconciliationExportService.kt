package com.feedback.service.imports

import com.feedback.security.WebPrincipal
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.repository.imports.ImportReconciliationRecord
import com.feedback.repository.imports.ImportReconciliationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

@Service
class ImportReconciliationExportService(
    private val repository: ImportReconciliationRepository,
    private val mapper: ObjectMapper,
    private val businessAudits: BusinessAuditService? = null,
    private val fallbackAudits: SystemAuditMutationRepository? = null
) {
    @Transactional
    fun export(batchId: String): Pair<String, ByteArray> {
        val records = repository.load(batchId).records
        val workbook = SXSSFWorkbook(100).apply { setCompressTempFiles(true) }
        val output = ByteArrayOutputStream()
        try {
            val sheet = workbook.createSheet("对账报告")
            val headers = listOf(
                "source_channel", "source_record_id", "title", "description", "assignee", "masking_status",
                "processing_status", "row_status", "fail_code", "fail_reason", "attachment_count", "available_attachment_count"
            )
            val headerStyle = workbook.createCellStyle().apply {
                fillForegroundColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                setFont(workbook.createFont().apply { bold = true })
            }
            sheet.createRow(0).also { row ->
                row.heightInPoints = 24f
                headers.forEachIndexed { i, value -> row.createCell(i).apply {
                    setCellValue(value)
                    cellStyle = headerStyle
                } }
            }
            records.forEachIndexed { index, record ->
                val row = sheet.createRow(index + 1)
                headers.forEachIndexed { i, key -> row.createCell(i).setCellValue(safeCell(value(record, key))) }
            }
            val widths = intArrayOf(16, 22, 30, 48, 20, 18, 20, 20, 24, 42, 20, 28)
            widths.forEachIndexed { index, characters -> sheet.setColumnWidth(index, characters * 256) }
            sheet.createFreezePane(0, 1)
            sheet.setAutoFilter(org.apache.poi.ss.util.CellRangeAddress(0, records.size, 0, headers.lastIndex))
            workbook.write(output)
        } finally {
            workbook.close()
        }
        val principal = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
            ?: importUnauthorized("AUTHENTICATION_REQUIRED")
        val detail=mapOf("rows" to records.size,"format" to "XLSX","containsAttachmentBinary" to false)
        if(businessAudits!=null)businessAudits.append(BusinessAuditEvent(principal.userId,
            "IMPORT_RECONCILIATION_EXPORT","SECURITY","SUCCESS","IMPORT_BATCH",batchId,
            java.util.UUID.randomUUID().toString(),null,detail))
        else importStateRequired(fallbackAudits) { "IMPORT_RECONCILIATION_AUDIT_UNAVAILABLE" }.append(
            SystemAuditInsertPo(principal.userId,"IMPORT_RECONCILIATION_EXPORT","SECURITY","SUCCESS",
                "IMPORT_BATCH",batchId,mapper.writeValueAsString(detail))
        )
        val timestamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        return "batch_${batchId}_${timestamp}_reconciliation.xlsx" to output.toByteArray()
    }

    private fun safeCell(value: Any?): String {
        val text = value?.toString().orEmpty().take(5_000)
        return if (text.firstOrNull() in setOf('=', '+', '-', '@')) "'$text" else text
    }

    private fun value(record: ImportReconciliationRecord, key: String): Any? = when (key) {
        "source_channel" -> record.sourceChannel
        "source_record_id" -> record.sourceRecordId
        "title" -> record.title
        "description" -> record.description
        "assignee" -> record.assignee
        "masking_status" -> record.maskingStatus
        "processing_status" -> record.processingStatus
        "row_status" -> record.rowStatus
        "fail_code" -> record.failCode
        "fail_reason" -> record.failReason
        "attachment_count" -> record.attachmentCount
        "available_attachment_count" -> record.availableAttachmentCount
        else -> importAbort("IMPORT_RECONCILIATION_COLUMN_UNKNOWN:$key")
    }
}
