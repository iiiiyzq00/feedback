package com.feedback.model.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import java.io.InputStream
import java.time.Instant

/** 上传对象的完整输入；调用方负责在调用结束后关闭原始流。 */
data class PutAttachmentObjectCommand(
    val key: AttachmentObjectKey,
    val content: InputStream,
    val contentLength: Long,
    val contentType: String,
    val checksumSha256Base64: String? = null
) {
    init {
        if (contentLength < 0) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_OBJECT_CONTENT_LENGTH_INVALID")
        }
        if (contentType.isBlank()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_OBJECT_CONTENT_TYPE_BLANK")
        }
    }
}

/** 对象元数据；`null` 由存储边界表示对象不存在。 */
data class AttachmentObjectMetadata(
    val backend: AttachmentStorageBackend,
    val bucket: String,
    val key: AttachmentObjectKey,
    val version: String?,
    val etag: String?,
    val contentLength: Long,
    val contentType: String?,
    val lastModified: Instant?
)

/** 受控对象读取结果；关闭本对象会关闭底层网络或文件流。 */
class AttachmentObjectRead(
    val metadata: AttachmentObjectMetadata,
    val content: InputStream
) : AutoCloseable {
    /** 关闭底层对象内容流并释放网络连接。 */
    override fun close() {
        content.close()
    }
}

/** 对象删除命令；版本为空时仍必须按对象键幂等删除。 */
data class DeleteAttachmentObjectCommand(
    val key: AttachmentObjectKey,
    val version: String? = null
)
