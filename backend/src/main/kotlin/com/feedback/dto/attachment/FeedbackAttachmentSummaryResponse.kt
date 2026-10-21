package com.feedback.dto.attachment

import com.feedback.service.attachment.*

import com.feedback.common.AttachmentContentReviewStatus
import com.feedback.common.AttachmentDecryptionStatus
import com.feedback.common.AttachmentIntegrityStatus
import com.feedback.common.AttachmentMalwareScanStatus
import com.feedback.common.AttachmentSecurityStatus
import com.feedback.application.port.feedback.attachment.AttachmentListFacts
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.FieldScopedResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 反馈的附件摘要。
 *
 * 本响应只包含展示和安全门禁状态，不包含存储路径、密文、附件正文或访问令牌。
 *
 * @property id 平台附件 ID，用于后续申请一次性安全查看；必填。
 * @property attachmentType 附件类型，包括截图、注释截图、图片、视频、录屏或日志；取值由权威附件类型约束定义。
 * @property mimeType MIME 类型；来源未提供时为空。
 * @property fileSizeBytes 文件大小，单位为字节；来源未提供时为空。
 * @property width 图片或视频宽度，单位为像素；不适用或未知时为空。
 * @property height 图片或视频高度，单位为像素；不适用或未知时为空。
 * @property durationSeconds 视频时长，单位为秒；不适用或未知时为空。
 * @property uploadStatus 上传状态；按权威附件记录原值返回。
 * @property securityStatus 附件综合安全状态；未知时为空。
 * @property integrityStatus 完整性校验状态；未知时为空。
 * @property decryptionStatus 解密状态；未知时为空。
 * @property malwareScanStatus 恶意软件扫描状态；未知时为空。
 * @property contentReviewStatus 内容安全审核状态；未知时为空。
 * @property sourceKind 附件来源类型，如 SDK 或 JIRA；未知时为空。
 * @property retentionUntil 附件保留截止时间，业务时区为 Asia/Shanghai；永久保留时为空。
 * @property createdAt 附件记录创建时间，业务时区为 Asia/Shanghai；历史缺失时为空。
 * @property available 当前查询时刻是否满足直接查看的全部安全门禁；读取内容时仍会实时校验权限与安全状态。
 * @property previewStatus 私有媒体派生预览状态；不包含对象键或访问地址。
 */
@Schema(
    description = "反馈的附件安全摘要；不包含存储路径、密文、正文或访问令牌。除 id 外，字段可能因 DataFieldPolicy 被省略"
)
/** HTTP 附件安全摘要；只暴露字段策略允许的元数据和瞬时可用状态。 */
data class FeedbackAttachmentSummaryResponse(
    @field:Schema(
        description = "平台附件 ID；系统字段，始终返回且不受 DataFieldPolicy 省略，用于申请一次性安全查看",
        example = "22",
        requiredMode = Schema.RequiredMode.REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val id: Long,
    @field:Schema(
        description = "附件类型；SCREENSHOT=截图、ANNOTATED_SCREENSHOT=注释截图、SCREEN_RECORDING=录屏、IMAGE=图片、VIDEO=视频、LOG=日志；DataFieldPolicy 拒绝时省略",
        example = "SCREENSHOT",
        allowableValues = ["SCREENSHOT", "ANNOTATED_SCREENSHOT", "SCREEN_RECORDING", "IMAGE", "VIDEO", "LOG"],
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val attachmentType: String,
    @field:Schema(
        description = "MIME 类型；来源未提供时为 null，DataFieldPolicy 拒绝时省略",
        example = "image/png",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val mimeType: String?,
    @field:Schema(
        description = "文件大小，单位为字节；来源未提供时为 null，DataFieldPolicy 拒绝时省略",
        example = "2048",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val fileSizeBytes: Long?,
    @field:Schema(
        description = "图片或视频宽度，单位为像素；不适用或未知时为 null，DataFieldPolicy 拒绝时省略",
        example = "1280",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val width: Int?,
    @field:Schema(
        description = "图片或视频高度，单位为像素；不适用或未知时为 null，DataFieldPolicy 拒绝时省略",
        example = "720",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val height: Int?,
    @field:Schema(
        description = "视频时长，单位为秒；不适用或未知时为 null，DataFieldPolicy 拒绝时省略",
        example = "12",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val durationSeconds: Int?,
    @field:Schema(
        description = "上传状态；PENDING=待上传、UPLOADED=已上传、VERIFIED=已校验、FAILED=失败；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "VERIFIED",
        allowableValues = ["PENDING", "UPLOADED", "VERIFIED", "FAILED"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val uploadStatus: String?,
    @field:Schema(
        description = "综合安全状态；QUARANTINED=隔离处理、VERIFIED=安全校验通过、REJECTED=拒绝；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "VERIFIED",
        allowableValues = ["QUARANTINED", "VERIFIED", "REJECTED"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val securityStatus: String?,
    @field:Schema(
        description = "完整性校验状态；PENDING=待校验、VERIFIED=通过、FAILED=失败；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "VERIFIED",
        allowableValues = ["PENDING", "VERIFIED", "FAILED"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val integrityStatus: String?,
    @field:Schema(
        description = "解密状态；NOT_REQUIRED=无需解密、PENDING=待处理、DECRYPTED=已解密、FAILED=失败；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "DECRYPTED",
        allowableValues = ["NOT_REQUIRED", "PENDING", "DECRYPTED", "FAILED"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val decryptionStatus: String?,
    @field:Schema(
        description = "恶意软件扫描状态；PENDING=待扫描、CLEAN=安全、INFECTED=检出风险、ERROR=扫描异常；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "CLEAN",
        allowableValues = ["PENDING", "CLEAN", "INFECTED", "ERROR"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val malwareScanStatus: String?,
    @field:Schema(
        description = "内容审核状态；PENDING=待审核、PASS=通过、REVIEW_NEEDED=需复核、REJECTED=拒绝；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "PASS",
        allowableValues = ["PENDING", "PASS", "REVIEW_NEEDED", "REJECTED"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val contentReviewStatus: String?,
    @field:Schema(
        description = "附件来源类型；SDK=客户端、IMPORT=导入、STATION_FEEDBACK=学习机站内反馈、JIRA=Jira；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "JIRA",
        allowableValues = ["SDK", "IMPORT", "STATION_FEEDBACK", "JIRA"],
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val sourceKind: String?,
    @field:Schema(
        description = "附件保留截止时间，格式为 date-time，业务时区 Asia/Shanghai；永久保留时为 null，DataFieldPolicy 拒绝时省略",
        example = "2026-09-29T16:30:00",
        format = "date-time",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val retentionUntil: LocalDateTime?,
    @field:Schema(
        description = "附件记录创建时间，格式为 date-time，业务时区 Asia/Shanghai；历史缺失时为 null，DataFieldPolicy 拒绝时省略",
        example = "2026-08-29T16:30:00",
        format = "date-time",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val createdAt: LocalDateTime?,
    @field:Schema(
        description = "当前查询时刻是否满足删除、存储内容、安全、完整性、解密、恶意软件、内容审核和保留期门禁；不替代实时授权，DataFieldPolicy 拒绝时省略",
        example = "true",
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val available: Boolean,
    @field:Schema(
        description = "媒体派生预览状态；PENDING/PROCESSING 表示后台生成中，AVAILABLE 可申请一次性预览授权，FAILED 降级为点击查看原件；DataFieldPolicy 拒绝时省略",
        allowableValues = ["PENDING", "PROCESSING", "AVAILABLE", "FAILED"],
        example = "AVAILABLE",
        nullable = true,
        requiredMode = Schema.RequiredMode.NOT_REQUIRED,
        accessMode = Schema.AccessMode.READ_ONLY
    )
    val previewStatus: String? = null,
    @field:Schema(hidden = true)
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse {
    companion object {
        /** 将强类型内部事实转换为保持既有字符串编码的 HTTP 响应。 */
        internal fun from(
            facts: AttachmentListFacts,
            fieldPolicy: DataFieldPolicy
        ) = FeedbackAttachmentSummaryResponse(
            id = facts.id,
            attachmentType = facts.attachmentType?.wireValue.orEmpty(),
            mimeType = facts.mimeType,
            fileSizeBytes = facts.fileSizeBytes,
            width = facts.width,
            height = facts.height,
            durationSeconds = facts.durationSeconds,
            uploadStatus = facts.uploadStatus?.code,
            securityStatus = facts.securityStatus?.wireValue,
            integrityStatus = facts.integrityStatus?.code,
            decryptionStatus = facts.decryptionStatus?.code,
            malwareScanStatus = facts.malwareScanStatus?.code,
            contentReviewStatus = facts.contentReviewStatus?.code,
            sourceKind = facts.sourceKind?.code,
            retentionUntil = facts.retentionUntil,
            createdAt = facts.createdAt,
            available = AttachmentAvailabilityPolicy.isAvailable(facts),
            previewStatus = facts.mediaPreviewStatus?.code,
            fieldPolicy = fieldPolicy
        )
    }
}

/**
 * 附件摘要只声明当前是否可查看，不能替代流式读取时的实时权限与安全状态复核。
 */
private object AttachmentAvailabilityPolicy {
    /** 使用强类型状态计算列表快照；任何未知数据库编码均被映射为 null 并失败关闭。 */
    fun isAvailable(facts: AttachmentListFacts): Boolean =
        facts.deletedAt == null &&
            facts.storedContentPresent &&
            facts.securityStatus == AttachmentSecurityStatus.VERIFIED &&
            facts.integrityStatus == AttachmentIntegrityStatus.VERIFIED &&
            facts.decryptionStatus in VIEWABLE_DECRYPTION_STATUSES &&
            facts.malwareScanStatus == AttachmentMalwareScanStatus.CLEAN &&
            facts.contentReviewStatus == AttachmentContentReviewStatus.PASS &&
            (facts.retentionUntil == null || facts.retentionUntil.isAfter(facts.queryTime))

    private val VIEWABLE_DECRYPTION_STATUSES = setOf(
        AttachmentDecryptionStatus.NOT_REQUIRED,
        AttachmentDecryptionStatus.DECRYPTED
    )
}
