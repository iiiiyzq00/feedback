package com.feedback.common

import java.util.Locale

/**
 * 反馈的权威物理来源渠道。
 *
 * 数据库与 HTTP 仍保存和接收既有字符串；应用层在分支、校验和集合运算前通过本类型解析，避免各用例
 * 各自维护五渠道常量。未知字符串返回 null，由具体边界沿用原有错误码或失败关闭语义。
 */
enum class FeedbackChannel {
    SDK,
    STATION_FEEDBACK,
    JIRA,
    QUALITY,
    REDBLACK;

    val wireValue: String get() = name
    val logicalChannel: LogicalChannel
        get() = if (this == SDK || this == STATION_FEEDBACK) LogicalChannel.LEARNING_MACHINE
        else LogicalChannel.valueOf(name)

    companion object {
        val feedback: Set<FeedbackChannel> = entries.toSet()
        val feedbackWireValues: Set<String> = entries.mapTo(linkedSetOf(), FeedbackChannel::wireValue)

        /** HTTP 等兼容边界可忽略大小写和首尾空白。 */
        fun fromWire(value: String?): FeedbackChannel? = value
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { normalized -> entries.firstOrNull { it.wireValue == normalized } }

        /** 数据库存量事实必须精确匹配，未知大小写或空白一律失败关闭。 */
        fun fromStorage(value: String?): FeedbackChannel? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 业务聚合渠道；SDK 与学习机站内反馈必须合并为同一个学习机渠道，其余渠道保持一一对应。 */
enum class LogicalChannel {
    LEARNING_MACHINE,
    JIRA,
    QUALITY,
    REDBLACK;

    val wireValue: String get() = name

    companion object {
        fun from(channel: FeedbackChannel): LogicalChannel = channel.logicalChannel
        fun fromPhysicalWire(value: String?): LogicalChannel? = FeedbackChannel.fromWire(value)?.logicalChannel
        fun fromPhysicalStorage(value: String?): LogicalChannel? = FeedbackChannel.fromStorage(value)?.logicalChannel
    }
}

/**
 * 平台统一处理状态。`fromBoundary` 只吸收现有 HTTP/旧内容表别名，不改变对外字符串和值域错误码。
 */
enum class WorkflowStatus {
    NEW,
    DISTRIBUTED,
    PROCESSING,
    SUSPENDED,
    RESOLVED,
    CLOSED;

    val wireValue: String get() = name

    companion object {
        fun fromBoundary(value: String?): WorkflowStatus? = when (value?.uppercase(Locale.ROOT)) {
            "PENDING", "OPEN", "NEW" -> NEW
            "DISTRIBUTED" -> DISTRIBUTED
            "PROCESSING", "IN_PROGRESS" -> PROCESSING
            "SUSPENDED" -> SUSPENDED
            "RESOLVED" -> RESOLVED
            "CLOSED" -> CLOSED
            else -> null
        }
    }
}

/**
 * 诊断证据的公开规范类型。存量 SDK 小写键在应用层规范化；未知扩展值仍按大写字符串透传，避免破坏兼容性。
 */
enum class DiagnosticType(val storageAlias: String? = null) {
    APPLICATION_LOG("app_internal_logs"),
    ACCOUNT_CONTEXT("account_context"),
    PRIVATE_FILE_MANIFEST("private_file_manifest"),
    USER_ID,
    AUTHENTICATED_USER_ID,
    DEVICE_SN;

    val wireValue: String get() = name

    companion object {
        fun normalizeWire(value: String): String {
            val normalized = value.trim()
            return entries.firstOrNull {
                it.wireValue.equals(normalized, ignoreCase = true) || it.storageAlias == normalized
            }?.wireValue ?: normalized.uppercase(Locale.ROOT)
        }
    }
}

/** 数据库附件类型的权威集合；HTTP DTO 继续输出相同字符串。 */
enum class AttachmentType {
    SCREENSHOT,
    ANNOTATED_SCREENSHOT,
    SCREEN_RECORDING,
    LOG,
    IMAGE,
    VIDEO;

    val wireValue: String get() = name

    companion object {
        fun fromWire(value: String?): AttachmentType? = value?.trim()?.uppercase(Locale.ROOT)?.let { normalized ->
            entries.firstOrNull { it.wireValue == normalized }
        }

        fun fromStorage(value: String?): AttachmentType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 附件综合安全状态；只有 VERIFIED 可进入后续完整安全门判断。 */
enum class AttachmentSecurityStatus {
    QUARANTINED,
    VERIFIED,
    REJECTED;

    val wireValue: String get() = name

    companion object {
        fun fromWire(value: String?): AttachmentSecurityStatus? = value?.trim()?.uppercase(Locale.ROOT)?.let { normalized ->
            entries.firstOrNull { it.wireValue == normalized }
        }

        fun fromStorage(value: String?): AttachmentSecurityStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** 一次性附件授权用途；用途参与数据库锁定与消费，不能把原始日志授权降级为普通页面查看。 */
enum class AttachmentGrantPurpose(val code: String) {
    INLINE_VIEW("INLINE_VIEW"),
    DERIVED_PREVIEW("DERIVED_PREVIEW"),
    ORIGINAL_LOG_DOWNLOAD("ORIGINAL_LOG_DOWNLOAD")
}
