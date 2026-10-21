package com.feedback.dto.sdk

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * SDK 反馈 ACK 响应
 */
data class SdkFeedbackAck(
    @JsonProperty("client_feedback_id")
    val clientFeedbackId: String,
    @JsonProperty("receipt_id")
    val receiptId: String,
    @JsonProperty("status")
    val status: String = "ENCRYPTED_ACCEPTED"
)

/** SdkFeedbackProgress 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class SdkFeedbackProgress(
    @JsonProperty("receipt_id") val receiptId:String,
    @JsonProperty("client_feedback_id") val clientFeedbackId:String,
    @JsonProperty("status") val status:String,
    @JsonProperty("stage") val stage:String,
    @JsonProperty("progress_percent") val progressPercent:Int,
    @JsonProperty("retryable") val retryable:Boolean,
    @JsonProperty("received_at") val receivedAt:java.time.LocalDateTime,
    @JsonProperty("processed_at") val processedAt:java.time.LocalDateTime?
)

/**
 * 附件上传响应
 */
data class SdkAttachmentAck(
    @JsonProperty("client_attachment_id")
    val clientAttachmentId: String,
    @JsonProperty("status")
    val status: String
)

/** SdkAttachmentMetadata 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class SdkAttachmentMetadata(
    @JsonProperty("client_attachment_id")
    val clientAttachmentId: String,
    @JsonProperty("type")
    val type: String,
    @JsonProperty("mime")
    val mime: String?,
    @JsonProperty("size_bytes")
    val sizeBytes: Long,
    @JsonProperty("sha256")
    val sha256: String,
    @JsonProperty("width")
    val width: Int? = null,
    @JsonProperty("height")
    val height: Int? = null,
    @JsonProperty("duration_seconds")
    val durationSeconds: Int? = null,
    @JsonProperty("annotation_json")
    val annotationJson: String? = null,
    @JsonProperty("encrypted_data_key")
    val encryptedDataKey: String? = null,
    @JsonProperty("algorithm_version")
    val algorithmVersion: String? = null
)

/**
 * 诊断事件上报请求
 */
data class SdkTelemetryRequest(
    @JsonProperty("events")
    val events: List<TelemetryEventDto>
)

/** TelemetryEventDto 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class TelemetryEventDto(
    @JsonProperty("client_feedback_id")
    val clientFeedbackId: String?,
    @JsonProperty("failure_stage")
    val failureStage: String,
    @JsonProperty("error_type")
    val errorType: String,
    @JsonProperty("network_type")
    val networkType: String?,
    @JsonProperty("retry_count")
    val retryCount: Int,
    @JsonProperty("attachment_size_bytes")
    val attachmentSizeBytes: Long?,
    @JsonProperty("sdk_version")
    val sdkVersion: String?,
    @JsonProperty("app_version")
    val appVersion: String?,
    @JsonProperty("duration_ms")
    val durationMs: Long?
)
