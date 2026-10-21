package com.feedback.model.imports

import java.time.LocalDateTime

/** 导入行领域快照；保存解析状态、受控错误类别及来源定位信息。 */
data class ImportRow(
    var id: Long = 0,
    val batchId: Long,
    val recordType: String,
    val sheetName: String? = null,
    val headerHash: ByteArray? = null,
    val sheetRowCount: Int? = null,
    val sheetColCount: Int? = null,
    val rowNumber: Int? = null,
    val sliceNo: Int? = null,
    val rowFingerprint: ByteArray? = null,
    var businessKeyHash: ByteArray? = null,
    var rowStatus: String? = null,
    var feedbackSourceId: Long? = null,
    var qualityStage: String? = null,
    var qualityErrorCode: String? = null,
    var qualityErrorSummary: String? = null,
    var qualitySuggestion: String? = null,
    var privacyReviewDecision: String? = null,
    var privacyReviewReason: String? = null,
    var privacyReviewedBy: Long? = null,
    var privacyReviewedAt: LocalDateTime? = null,
    var privacyReviewRuleVersion: String? = null,
    var privacyReviewInputSha256: ByteArray? = null,
    var privacyReviewOutputSha256: ByteArray? = null,
    var privacyReviewFindings: String? = null,
    var rawRowContentEnc: ByteArray? = null,
    var rawNonce: ByteArray? = null,
    var rawTag: ByteArray? = null,
    var rawKeyId: String? = null,
    var rawAlgorithm: String? = null,
    val rawRetentionUntil: LocalDateTime? = null,
    var rawDeletedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
