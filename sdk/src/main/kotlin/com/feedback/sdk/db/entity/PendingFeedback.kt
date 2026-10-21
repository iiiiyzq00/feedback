package com.feedback.sdk.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RoomDB 实体 — 待提交反馈
 * 本地状态机：PENDING_BODY → PENDING_ATTACHMENTS / RETRY_EXHAUSTED → COMPLETED / PERMANENT_FAILED
 */
@Entity(tableName = "pending_feedback")
data class PendingFeedback(
    @PrimaryKey
    val clientFeedbackId: String,        // UUIDv7，客户端生成
    val packageName: String,
    val appVersion: String?,
    val functionModule: String?,
    val issueType: String?,
    val sourceEventTime: Long,           // 毫秒时间戳
    val osVersion: String?,
    val networkType: String?,
    val triggerType: String?,            // 取值：FLOAT_BUTTON、NOTIFICATION、LAUNCHER、DEEPLINK、API_CALL

    // 加密信封
    val encryptedDataKey: ByteArray?,
    val payloadNonce: ByteArray?,
    val payloadCiphertext: ByteArray?,
    val payloadTag: ByteArray?,
    val transportKeyId: String = "sdk-transport-v2",

    // 联系方式
    val contactAllowed: Boolean = false,
    val contactNonce: ByteArray? = null,
    val contactCiphertext: ByteArray? = null,
    val contactTag: ByteArray? = null,

    // 状态：PENDING_BODY | PENDING_ATTACHMENTS | RETRY_EXHAUSTED | COMPLETED | PERMANENT_FAILED
    val status: String = "PENDING_BODY",
    val retryCount: Int = 0,
    val lastErrorCode: String? = null,
    val receiptId: String? = null,

    // 时间戳
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
