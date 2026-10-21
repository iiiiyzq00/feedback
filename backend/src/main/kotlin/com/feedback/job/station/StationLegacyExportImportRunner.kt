package com.feedback.job.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.system.exitProcess

/** Station 历史导入命令入口；文件读取在事务外，批次写入使用独立短事务。 */
@Component
@ConditionalOnProperty(name = ["feedback.station.legacy-import.file"])
class StationLegacyExportImportRunner(
    private val service: StationLegacyExportImportService,
    private val arguments: ApplicationArguments
) : ApplicationRunner {
    /** 执行一次有界 Station 导入或恢复批次；文件 I/O 位于事务外，批次写入使用独立短事务。 */
    override fun run(args: ApplicationArguments) {
        val file = args.getOptionValues("feedback.station.legacy-import.file")?.singleOrNull()
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_FILE_ARGUMENT_REQUIRED")
        val result = service.importControlledExport(Path.of(file))
        println("STATION_LEGACY_IMPORT_OK rows=${result.fileRows} inserted=${result.inserted} enriched=${result.enriched} evidenceQueued=${result.evidenceQueued} recovered=${result.recovered} invalidContacts=${result.invalidContacts} skipped=${result.skipped} busySkipped=${result.busySkipped} batches=${result.successfulBatches} sha256=${result.fileSha256}")
        exitProcess(0)
    }
}
