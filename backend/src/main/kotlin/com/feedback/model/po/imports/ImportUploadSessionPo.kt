package com.feedback.model.po.imports

import java.time.LocalDateTime

data class ImportUploadSessionPo(
    val id: Long, val uploadFileId: String, val sourceChannel: String, val totalChunks: Int,
    val totalSizeBytes: Long, val uploadStatus: String, val expiresAt: LocalDateTime,
    val sheetName: String?, val headerHash: ByteArray?, val qualityErrorCode: String?,
    val qualityErrorSummary: String?, val qualitySuggestion: String?
)
