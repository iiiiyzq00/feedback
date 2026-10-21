package com.feedback.dto.sdk

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * SDK 反馈主体请求 — POST /api/v1/sdk/feedback
 */
data class SdkFeedbackRequest(
    @field:NotBlank
    @field:Size(max = 64)
    @JsonProperty("client_feedback_id")
    val clientFeedbackId: String,

    @field:NotBlank
    @JsonProperty("package_name")
    val packageName: String,

    @JsonProperty("app_version")
    val appVersion: String?,

    @JsonProperty("function_module")
    val functionModule: String?,

    @JsonProperty("issue_type")
    val issueType: String?,

    @field:NotBlank
    @JsonProperty("source_event_time")
    val sourceEventTime: String,  // ISO-8601

    @JsonProperty("os_version")
    val osVersion: String?,

    @JsonProperty("network_type")
    val networkType: String?,

    @JsonProperty("trigger_type")
    @field:Size(max = 64)
    val triggerType: String?,

    @field:NotNull @field:Valid
    @JsonProperty("encrypted_payload")
    val encryptedPayload: EncryptedPayloadDto,

    @field:Valid
    @JsonProperty("encrypted_contact_payload")
    val encryptedContactPayload: EncryptedContactPayloadDto?,

    @JsonProperty("attachment_manifest")
    @field:Size(max = 20)
    val attachmentManifest: List<AttachmentManifestDto>? = null,

    @JsonProperty("schema_version")
    val schemaVersion: String = "sdk-form-v1"
)

/** EncryptedPayloadDto 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class EncryptedPayloadDto(
    @field:NotBlank
    @JsonProperty("key_id")
    val keyId: String,

    @field:NotBlank
    @JsonProperty("algorithm")
    val algorithm: String,

    @field:NotBlank
    @JsonProperty("encrypted_data_key")
    val encryptedDataKey: String,  // base64

    @field:NotBlank
    @JsonProperty("nonce")
    val nonce: String,  // base64

    @field:NotBlank
    @JsonProperty("ciphertext")
    val ciphertext: String,  // base64

    @field:NotBlank
    @JsonProperty("tag")
    val tag: String  // base64
)

/** EncryptedContactPayloadDto 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class EncryptedContactPayloadDto(
    @field:NotBlank
    @JsonProperty("nonce")
    val nonce: String,

    @field:NotBlank
    @JsonProperty("ciphertext")
    val ciphertext: String,

    @field:NotBlank
    @JsonProperty("tag")
    val tag: String,

    @JsonProperty("contact_allowed")
    val contactAllowed: Boolean = false
)

/** AttachmentManifestDto 的外部协议模型；字段校验和序列化名称必须保持 HTTP 兼容。 */
data class AttachmentManifestDto(
    @JsonProperty("client_attachment_id")
    val clientAttachmentId: String,

    @JsonProperty("type")
    val type: String,  // 取值：SCREENSHOT、ANNOTATED_SCREENSHOT、SCREEN_RECORDING、LOG

    @JsonProperty("size_bytes")
    val sizeBytes: Long,

    @JsonProperty("sha256")
    val sha256: String?
)
