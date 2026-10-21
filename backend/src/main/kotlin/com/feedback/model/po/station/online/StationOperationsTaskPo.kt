package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOperationsTaskPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOperationsTaskPo(
    val sourceId: Long,
    val receiptId: ByteArray,
    val apiVersion: String,
    val productId: String,
    val appId: String?,
    val platform: String,
    val subChannel: String,
    val status: String,
    val processingStage: String,
    val contactStatus: String,
    val attributeStatus: String,
    val retryCount: Int,
    val maxRetryCount: Int,
    val nextRetryAt: LocalDateTime?,
    val errorCode: String?,
    val errorSummary: String?,
    val receivedAt: LocalDateTime,
    val processedAt: LocalDateTime?,
    val retryable: Boolean
)
