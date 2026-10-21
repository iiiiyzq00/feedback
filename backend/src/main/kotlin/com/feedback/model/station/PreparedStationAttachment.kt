package com.feedback.model.station

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

/** 事务外准备完成的 Station 附件；记录文件所有权以支持回滚补偿清理。 */
data class PreparedStationAttachment(
    val sourceId: Long,
    val sourceAttachmentId: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val fileSha256: ByteArray,
    val storagePath: Path,
    val retentionUntil: LocalDateTime,
    val ownsFile: Boolean
) {
    /** 清理本次调用拥有的临时或隔离文件；不得删除由其他请求复用的既有文件。 */
    fun discard() {
        if (ownsFile) {
            runCatching { Files.deleteIfExists(storagePath) }
                .onFailure { error ->
                    log.warn(
                        "Station attachment cleanup failed: sourceId={} errorType={}",
                        sourceId,
                        error.javaClass.simpleName
                    )
                }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PreparedStationAttachment::class.java)
    }
}
