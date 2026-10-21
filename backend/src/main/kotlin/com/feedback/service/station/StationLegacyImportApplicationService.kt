package com.feedback.service.station

import com.feedback.model.station.StationLegacyExportRow
import com.feedback.model.station.StationLegacyRecoveryRow
import com.feedback.model.station.StationLegacyBatchResult

/** Station Legacy 批次级写入契约；每次调用对应一个独立短事务。 */
interface StationLegacyImportApplicationService {
    /** 导入一个不超过仓储上限的预解析批次。 */
    fun importBatch(
        rows: List<StationLegacyExportRow>,
        runId: String,
        fileSha256: String,
        batchNumber: Int
    ): StationLegacyBatchResult

    /** 恢复游标之后的一页失败记录。 */
    fun recoverPage(
        afterSourceId: Long,
        rowsByRecordId: Map<String, StationLegacyRecoveryRow>,
        runId: String,
        fileSha256: String,
        batchNumber: Int
    ): StationLegacyBatchResult
}
