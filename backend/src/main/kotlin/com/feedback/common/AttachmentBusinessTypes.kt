package com.feedback.common

/** 数据库与 HTTP 共用的附件上传状态；[code] 保持既有编码。 */
enum class AttachmentUploadStatus(val code: String) {
    PENDING("PENDING"),
    UPLOADED("UPLOADED"),
    VERIFIED("VERIFIED"),
    FAILED("FAILED");

    companion object {
        /** 在数据库边界解析稳定编码；未知值返回 null，业务门禁据此失败关闭。 */
        fun fromStorage(value: String?): AttachmentUploadStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 附件完整性校验状态；[code] 与历史数据库值完全一致。 */
enum class AttachmentIntegrityStatus(val code: String) {
    PENDING("PENDING"),
    VERIFIED("VERIFIED"),
    FAILED("FAILED");

    companion object {
        /** 在数据库边界解析稳定编码，未知值不降级为正常状态。 */
        fun fromStorage(value: String?): AttachmentIntegrityStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 附件解密状态；只有无需解密或已解密可以通过查看门禁。 */
enum class AttachmentDecryptionStatus(val code: String) {
    NOT_REQUIRED("NOT_REQUIRED"),
    PENDING("PENDING"),
    DECRYPTED("DECRYPTED"),
    FAILED("FAILED");

    companion object {
        /** 在数据库边界解析稳定编码，未知值返回 null。 */
        fun fromStorage(value: String?): AttachmentDecryptionStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 恶意软件扫描状态；只有 CLEAN 可以通过查看门禁。 */
enum class AttachmentMalwareScanStatus(val code: String) {
    PENDING("PENDING"),
    CLEAN("CLEAN"),
    INFECTED("INFECTED"),
    ERROR("ERROR");

    companion object {
        /** 在数据库边界解析稳定编码，未知值返回 null。 */
        fun fromStorage(value: String?): AttachmentMalwareScanStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 内容审核状态；只有 PASS 可以通过查看门禁。 */
enum class AttachmentContentReviewStatus(val code: String) {
    PENDING("PENDING"),
    PASS("PASS"),
    REVIEW_NEEDED("REVIEW_NEEDED"),
    REJECTED("REJECTED");

    companion object {
        /** 在数据库边界解析稳定编码，未知值返回 null。 */
        fun fromStorage(value: String?): AttachmentContentReviewStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 日志派生预览状态；状态推进由条件 SQL 保证幂等。 */
enum class AttachmentLogPreviewStatus(val code: String) {
    PENDING("PENDING"),
    AVAILABLE("AVAILABLE"),
    FAILED("FAILED");

    companion object {
        /** 在数据库边界解析稳定编码，未知值返回 null。 */
        fun fromStorage(value: String?): AttachmentLogPreviewStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 媒体派生预览及批量授权响应状态；HTTP 输出继续使用 [code]。 */
enum class AttachmentPreviewStatus(val code: String) {
    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    AVAILABLE("AVAILABLE"),
    FAILED("FAILED"),
    UNAVAILABLE("UNAVAILABLE");

    companion object {
        /** 在数据库边界解析稳定编码，未知值返回 null。 */
        fun fromStorage(value: String?): AttachmentPreviewStatus? = entries.firstOrNull { it.code == value }
    }
}

/** 平台控制的附件来源集合；[code] 保持既有数据库与 HTTP 编码。 */
enum class AttachmentSourceKind(val code: String) {
    SDK("SDK"),
    IMPORT("IMPORT"),
    STATION_FEEDBACK("STATION_FEEDBACK"),
    JIRA("JIRA");

    companion object {
        /** 在数据库边界解析稳定编码；历史未知来源返回 null 并保持不可提升语义。 */
        fun fromStorage(value: String?): AttachmentSourceKind? = entries.firstOrNull { it.code == value }
    }
}

/** 授权审计动作；动作 code 同时用于摘要和一次性授权用途绑定。 */
enum class AttachmentAuthorizationAction(val code: String) {
    DIRECT_VIEW("DIRECT_VIEW"),
    DERIVED_PREVIEW("DERIVED_PREVIEW"),
    ORIGINAL_LOG_DOWNLOAD("ORIGINAL_LOG_DOWNLOAD")
}

/** 附件授权审计操作；[code] 保持既有审计表编码。 */
enum class AttachmentAuditOperation(val code: String) {
    ATTACHMENT_DECRYPT("ATTACHMENT_DECRYPT"),
    ATTACHMENT_PREVIEW("ATTACHMENT_PREVIEW"),
    ORIGINAL_LOG_DOWNLOAD("ORIGINAL_LOG_DOWNLOAD")
}
