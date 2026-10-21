package com.feedback.service.sdk

import com.feedback.dto.sdk.SdkAttachmentAck
import org.springframework.web.multipart.MultipartFile

/** SDK 加密附件及分块上传服务契约；文件 I/O 不参与数据库事务。 */
interface SdkAttachmentService {
    /** 接收完整 SDK 附件，在事务外校验大小、摘要并解密落盘，再以短事务登记附件元数据。 */
    fun ingestAttachment(
        receiptId: String,
        appKey: String,
        metadataJson: String,
        encryptedFile: MultipartFile
    ): SdkAttachmentAck

    /** 接收单个 SDK 附件分片；校验分片身份、序号、大小和摘要，最后一片才触发有界组装。 */
    fun ingestAttachmentChunk(
        receiptId: String,
        appKey: String,
        metadataJson: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSha256: String,
        chunk: MultipartFile
    ): SdkAttachmentAck
}
