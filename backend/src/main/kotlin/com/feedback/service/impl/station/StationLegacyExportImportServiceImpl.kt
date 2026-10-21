package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.station.legacy.StationLegacyRepository
import com.feedback.model.station.StationLegacyBatchResult
import com.feedback.manager.station.StationLegacyExportFileReader
import com.feedback.service.station.StationLegacyExportImportService
import com.feedback.service.station.StationLegacyImportApplicationService
import com.feedback.model.station.StationLegacyImportResult
import com.feedback.model.station.StationLegacyRecoveryResult
import com.feedback.model.station.StationLegacyRecoveryRow
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.UUID

/** Station 历史导入与恢复编排实现；只协调显式启用的单次任务，权威写入全部委托批次服务。 */
@Service
class StationLegacyExportImportServiceImpl(
    private val files: StationLegacyExportFileReader,
    private val batches: StationLegacyImportApplicationService,
    private val audits: BusinessAuditService
) : StationLegacyExportImportService {
    /** 读取受控导出文件并按固定上限分批提交，文件 I/O 永远位于写事务之外。 */
    override fun importControlledExport(path: Path): StationLegacyImportResult {
        val runId = UUID.randomUUID().toString()
        return try {
            val file = files.readImport(path)
            var total = StationLegacyBatchResult()
            var batchCount = 0
            file.rows.chunked(StationLegacyRepository.MAX_BATCH_SIZE).forEachIndexed { index, rows ->
                total += batches.importBatch(rows, runId, file.sha256, index + 1)
                batchCount++
            }
            val result = StationLegacyImportResult(
                file.rows.size,
                total.inserted,
                total.enriched,
                total.evidenceQueued,
                total.recovered,
                total.invalidContacts,
                total.skipped,
                total.busySkipped,
                batchCount,
                file.sha256
            )
            audit("STATION_LEGACY_IMPORT_COMPLETED", "SUCCESS", runId, file.sha256, resultDetails(result))
            result
        } catch (error: Exception) {
            auditFailureWithoutMasking("STATION_LEGACY_IMPORT_FAILED", runId, error)
            throw error
        }
    }

    /** 扫描历史失败记录并按游标逐批恢复，任一批失败即停止且保留已提交批次。 */
    override fun recoverOperationFailures(path: Path): StationLegacyRecoveryResult {
        val runId = UUID.randomUUID().toString()
        return try {
            val file = files.readRecovery(path)
            val rows = file.rows.associateBy(StationLegacyRecoveryRow::recordId)
            var total = StationLegacyBatchResult()
            var cursor = 0L
            var batchCount = 0
            while (true) {
                val batch = batches.recoverPage(cursor, rows, runId, file.sha256, batchCount + 1)
                if (batch.candidateCount == 0) {
                    break
                }
                if (batch.nextSourceId <= cursor) {
                    throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_RECOVERY_CURSOR_STALLED")
                }
                cursor = batch.nextSourceId
                total += batch
                batchCount++
            }
            val result = StationLegacyRecoveryResult(
                total.candidateCount,
                total.matchedCount,
                total.recovered,
                total.skipped,
                total.busySkipped,
                batchCount,
                file.sha256
            )
            audit("STATION_LEGACY_OPERATION_RECOVERY_COMPLETED", "SUCCESS", runId, file.sha256, resultDetails(result))
            result
        } catch (error: Exception) {
            auditFailureWithoutMasking("STATION_LEGACY_OPERATION_RECOVERY_FAILED", runId, error)
            throw error
        }
    }

    /** 组装导入或恢复结果的安全审计详情，只保留摘要、计数与稳定状态。 */
    private fun resultDetails(result: StationLegacyImportResult): Map<String, Any?> = mapOf(
        "rows" to result.fileRows,
        "inserted" to result.inserted,
        "enriched" to result.enriched,
        "evidenceQueued" to result.evidenceQueued,
        "recovered" to result.recovered,
        "invalidContacts" to result.invalidContacts,
        "skipped" to result.skipped,
        "busySkipped" to result.busySkipped,
        "successfulBatches" to result.successfulBatches
    )

    /** 组装导入或恢复结果的安全审计详情，只保留摘要、计数与稳定状态。 */
    private fun resultDetails(result: StationLegacyRecoveryResult): Map<String, Any?> = mapOf(
        "targetRows" to result.targetRows,
        "matchedRows" to result.matchedRows,
        "recovered" to result.recovered,
        "skipped" to result.skipped,
        "busySkipped" to result.busySkipped,
        "successfulBatches" to result.successfulBatches
    )

    /** 记录导入失败审计；异常先归一为安全错误码，禁止写入原始文件内容。 */
    private fun auditFailure(operation: String, runId: String, error: Exception) {
        audit(operation, "FAILED", runId, null, mapOf("errorCode" to safeErrorCode(error)))
    }

    /** 尽力记录失败审计，但绝不能覆盖权威解析、批次、脏标记或成功审计异常。 */
    private fun auditFailureWithoutMasking(operation: String, runId: String, original: Exception) {
        runCatching { auditFailure(operation, runId, original) }
            .exceptionOrNull()
            ?.let(original::addSuppressed)
    }

    /** 投递 Station 导入审计事件；审计与业务批次按既定一致性边界提交。 */
    private fun audit(
        operation: String,
        outcome: String,
        runId: String,
        fileSha256: String?,
        detail: Map<String, Any?>
    ) {
        audits.enqueue(BusinessAuditEvent(
            null,
            operation,
            "SECURITY",
            outcome,
            "STATION_EXPORT",
            fileSha256?.take(32),
            runId,
            null,
            if (fileSha256 == null) detail else detail + ("fileSha256" to fileSha256)
        ))
    }

    /** 把异常归一为安全错误信息：Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun safeErrorCode(error: Exception): String = error.message?.takeIf {
        it.matches(Regex("^STATION_[A-Z0-9_:]{1,90}$"))
    }?.take(100) ?: "STATION_LEGACY_OPERATION_FAILED"
}
