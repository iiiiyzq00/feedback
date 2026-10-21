package com.feedback.repository.remote.attachment

import com.feedback.model.attachment.AttachmentObjectMetadata
import com.feedback.model.attachment.AttachmentObjectRead
import com.feedback.model.attachment.DeleteAttachmentObjectCommand
import com.feedback.model.attachment.AttachmentObjectKey
import com.feedback.model.attachment.PutAttachmentObjectCommand
import com.feedback.model.attachment.StoredAttachmentObject

/**
 * 附件对象存储端口。
 *
 * 业务层只处理对象键和具名元数据；鉴权、签名、端点及存储桶均封装在适配器内。
 * `delete` 必须具备幂等语义，对已不存在的对象也视为成功。
 */
interface AttachmentObjectStorage {
    /** 上传私有对象并返回服务端版本和 ETag；调用方负责校验回读内容。 */
    fun put(command: PutAttachmentObjectCommand): StoredAttachmentObject

    /** 读取对象元数据；明确不存在时返回 null，其余远程失败向上抛出。 */
    fun head(key: AttachmentObjectKey): AttachmentObjectMetadata?

    /** 打开对象内容流；调用方必须关闭返回值。 */
    fun read(key: AttachmentObjectKey): AttachmentObjectRead

    /** 幂等删除精确对象键及可选版本，不接受目录或通配符。 */
    fun delete(command: DeleteAttachmentObjectCommand)
}
