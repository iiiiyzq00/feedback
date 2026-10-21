package com.feedback.job.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.system.exitProcess

/** Station 历史恢复命令入口；只触发有界恢复服务并记录安全结果。 */
@Component
@ConditionalOnProperty(name = ["feedback.station.legacy-recovery.file"])
class StationLegacyRecoveryRunner(
    private val service: StationLegacyExportImportService,
    @Value("\${feedback.station.legacy-recovery.file}") private val file: String
) : ApplicationRunner {
    /** 执行一次有界 Station 导入或恢复批次；文件 I/O 位于事务外，批次写入使用独立短事务。 */
    override fun run(args: org.springframework.boot.ApplicationArguments) {
        if (file.isBlank()) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_RECOVERY_FILE_ARGUMENT_REQUIRED")
        }
        val result = service.recoverOperationFailures(Path.of(file))
        println("STATION_LEGACY_RECOVERY_OK target=${result.targetRows} matched=${result.matchedRows} recovered=${result.recovered} skipped=${result.skipped} busySkipped=${result.busySkipped} batches=${result.successfulBatches} sha256=${result.fileSha256}")
        exitProcess(0)
    }
}
