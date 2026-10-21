package com.feedback.model.attachment

import io.swagger.v3.oas.annotations.media.Schema
import java.io.InputStream
import java.time.LocalDateTime

/** 与 HTTP 和存储 SDK 无关的一次性内容源；调用方取得流后必须关闭。 */
fun interface AttachmentContentSource {
    /** 打开一次且由调用方关闭的底层流；实现不得记录令牌或绕过路径策略。 */
    fun openStream(): InputStream
}

/** 已消费授权对应的不可变流快照；不携带 Servlet、Spring Resource 或对象存储 SDK 类型。 */
data class SecureAttachmentContent(
    val source: AttachmentContentSource,
    val mimeType: String,
    val filename: String,
    val contentLength: Long? = null
) {
    /** 打开当前安全内容快照的流，调用方必须关闭。 */
    fun openStream(): InputStream = source.openStream()
}

/** 反馈附件一次性安全流描述；敏感令牌只允许在本次响应中交付。 */
@Schema(description = "反馈附件一次性安全流；页面按权限直接展示，原始令牌只在本响应中交付，禁止缓存、持久化或写日志")
data class AttachmentAccessGrant(
    @field:Schema(description = "一次性敏感令牌；绑定当前用户、反馈和附件，只能消费一次且不得写入日志")
    val grantId: String,
    @field:Schema(description = "授权过期时间，业务时区 Asia/Shanghai")
    val expiresAt: LocalDateTime,
    @field:Schema(description = "从签发时刻计算的剩余有效期，单位：秒", minimum = "30", maximum = "300")
    val expiresInSeconds: Long,
    @field:Schema(
        description = "交付模式；页面原件查看、派生媒体预览与原始日志下载用途隔离",
        allowableValues = ["ONE_TIME_SECURE_STREAM", "ONE_TIME_DERIVED_PREVIEW", "ONE_TIME_ORIGINAL_DOWNLOAD"]
    )
    val delivery: AttachmentDeliveryMode,
    @field:Schema(description = "包含一次性令牌的同源临时流地址，不是永久附件链接")
    val downloadUrl: String
)

/** 附件安全交付模式；三个稳定编码分别隔离普通查看、派生预览和原始日志下载。 */
@Schema(description = "附件安全流交付模式")
enum class AttachmentDeliveryMode {
    ONE_TIME_SECURE_STREAM,
    ONE_TIME_DERIVED_PREVIEW,
    ONE_TIME_ORIGINAL_DOWNLOAD
}
