package com.feedback.service.station

import com.feedback.model.station.StationLegacyImportResult
import com.feedback.model.station.StationLegacyRecoveryResult
import java.nio.file.Path

/** Station Legacy 文件级编排契约；负责有界分批，不持有数据库事务。 */
interface StationLegacyExportImportService {
    /** 导入一个已经显式授权的本地导出文件。 */
    fun importControlledExport(path: Path): StationLegacyImportResult

    /** 使用受控恢复文件修复可恢复的历史失败记录。 */
    fun recoverOperationFailures(path: Path): StationLegacyRecoveryResult
}
