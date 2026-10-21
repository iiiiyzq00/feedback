package com.feedback.model.sdk

import java.time.LocalDateTime

/**
 * SDK 加密反馈接入信封。可变字段对应接入状态机的受控迁移；持久化只允许通过 MyBatis Repository 边界。
 */
data class SdkIngest(
    var id: Long = 0,
    val receiptId: ByteArray = ByteArray(16),
    val clientFeedbackId: String = "",
    val appId: String = "",
    val appVersion: String? = null,
    val packageName: String? = null,
    val functionModule: String? = null,
    val issueType: String? = null,
    val sourceEventTime: LocalDateTime? = null,
    val osVersion: String? = null,
    val networkType: String? = null,
    val triggerType: String? = null,
    var encryptedDataKey: ByteArray? = null,
    var payloadNonce: ByteArray? = null,
    var payloadCiphertext: ByteArray? = null,
    var payloadTag: ByteArray? = null,
    var contactNonce: ByteArray? = null,
    var contactCiphertext: ByteArray? = null,
    var contactTag: ByteArray? = null,
    val contactAllowed: Boolean = false,
    var bodyProcessingStatus: String = "PENDING",
    var contactProcessingStatus: String = "NOT_PROVIDED",
    var urgencyClass: String = "NORMAL",
    val keyId: String = "",
    val algorithmVersion: String = "AES-256-GCM+RSA-OAEP-256",
    val schemaVersion: String = "sdk-form-v1",
    val requestSha256: ByteArray? = null,
    val requestSizeBytes: Int? = null,
    var ingestStatus: String = "RECEIVED",
    var dispositionStatus: String = "OPEN",
    var retryCount: Int = 0,
    var nextRetryAt: LocalDateTime? = null,
    var lastErrorCode: String? = null,
    var lastErrorDetail: String? = null,
    var feedbackSourceId: Long? = null,
    var ciphertextDeletedAt: LocalDateTime? = null,
    var retentionUntil: LocalDateTime? = null,
    val receivedAt: LocalDateTime = LocalDateTime.now(),
    var processedAt: LocalDateTime? = null
)
