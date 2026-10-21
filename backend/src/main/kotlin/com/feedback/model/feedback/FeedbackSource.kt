package com.feedback.model.feedback

import java.time.LocalDateTime

/** 四渠道统一反馈来源事实；持久化由 CoreFeedbackSourceMapper 负责。 */
data class FeedbackSource(
    var id: Long = 0,
    val publicId: ByteArray = ByteArray(16),
    val sourceChannel: String = "",
    val sourceRecordId: String = "",
    val appVersion: String? = null,
    val packageName: String? = null,
    val sourceEventTime: LocalDateTime? = null,
    val faultOccurredAt: LocalDateTime? = null,
    val faultTimeRaw: String? = null,
    val faultTimePrecision: String? = null,
    val faultTimeSource: String? = null,
    val faultTimeResolution: String = "PENDING",
    val sourceUpdateTime: LocalDateTime = LocalDateTime.now(),
    val ingestTime: LocalDateTime = LocalDateTime.now(),
    val importBatchId: Long? = null,
    val provinceCode: String? = null,
    val cityCode: String? = null,
    val districtCode: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
