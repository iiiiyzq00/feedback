package com.feedback.model.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

/** 受控对象键；拒绝绝对路径、路径穿越、反斜杠和控制字符。 */
@JvmInline
value class AttachmentObjectKey(val value: String) {
    init {
        validate(value.isNotBlank(), "ATTACHMENT_OBJECT_KEY_BLANK")
        validate(value.length <= MAX_LENGTH, "ATTACHMENT_OBJECT_KEY_TOO_LONG")
        validate(!value.startsWith('/'), "ATTACHMENT_OBJECT_KEY_ABSOLUTE")
        validate('\\' !in value, "ATTACHMENT_OBJECT_KEY_BACKSLASH")
        validate(value.none(Char::isISOControl), "ATTACHMENT_OBJECT_KEY_CONTROL_CHARACTER")
        validate(value.split('/').none { it.isEmpty() || it == "." || it == ".." }, "ATTACHMENT_OBJECT_KEY_INVALID_SEGMENT")
    }

    companion object {
        const val MAX_LENGTH = 1024

        /** 将对象键输入不变量失败统一映射为稳定客户端错误。 */
        private fun validate(valid: Boolean, reasonCode: String) {
            if (!valid) {
                throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
            }
        }
    }
}

/** 附件私有对象存储后端；[code] 保持数据库既有编码。 */
enum class AttachmentStorageBackend(val code: String) {
    S3_COMPATIBLE("S3_COMPATIBLE");

    companion object {
        /** 严格解析数据库存储后端；缺失或未知值均失败关闭。 */
        fun requireStorage(value: String?): AttachmentStorageBackend = entries.firstOrNull { it.code == value }
            ?: throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, "ATTACHMENT_STORAGE_BACKEND_UNKNOWN")
    }
}

/** 对象存储生命周期状态机；[code] 保持数据库既有编码。 */
enum class AttachmentObjectLifecycleStatus(val code: String) {
    PENDING("PENDING"),
    QUARANTINED("QUARANTINED"),
    AVAILABLE("AVAILABLE"),
    DELETE_PENDING("DELETE_PENDING"),
    DELETED("DELETED"),
    FAILED("FAILED");

    companion object {
        /** 严格解析数据库生命周期状态；缺失或未知值均失败关闭。 */
        fun requireStorage(value: String?): AttachmentObjectLifecycleStatus = entries.firstOrNull { it.code == value }
            ?: throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, "ATTACHMENT_OBJECT_STATUS_UNKNOWN")
    }
}

/** 对象写入成功后可由 Repository 原子登记的权威位置事实。 */
data class StoredAttachmentObject(
    val backend: AttachmentStorageBackend,
    val bucket: String,
    val key: AttachmentObjectKey,
    val version: String?,
    val etag: String?
)
