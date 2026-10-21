package com.feedback.sdk.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 附件记录 — 截图/录屏/日志
 */
@Entity(
    tableName = "attachment_record",
    foreignKeys = [
        ForeignKey(
            entity = PendingFeedback::class,
            parentColumns = ["clientFeedbackId"],
            childColumns = ["clientFeedbackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("clientFeedbackId")]
)
data class AttachmentRecord(
    @PrimaryKey
    val clientAttachmentId: String,      // UUID，客户端生成
    val clientFeedbackId: String,
    val type: String,                    // 取值：SCREENSHOT、ANNOTATED_SCREENSHOT、SCREEN_RECORDING、LOG
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: ByteArray?,
    val encryptedDataKey: ByteArray?,     // RSA-OAEP-256 封装的「仅附件」密钥
    val algorithmVersion: String = "FBA2-AES-256-GCM+RSA-OAEP-256",
    val localPath: String,               // 本地文件路径
    val parentAttachmentId: String?,     // 标注截图关联原图
    val annotationJson: String?,         // 圈选坐标 JSON
    val status: String = "PENDING",      // 取值：PENDING | UPLOADING | UPLOADED | FAILED
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
