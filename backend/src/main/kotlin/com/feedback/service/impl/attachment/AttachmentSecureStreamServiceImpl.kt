package com.feedback.service.impl.attachment

import com.feedback.application.port.feedback.attachment.AttachmentGrantStorePort
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.manager.attachment.AttachmentContentProvider
import com.feedback.manager.attachment.AttachmentSecureStreamOperations
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.feedback.model.attachment.SecureAttachmentContent
import com.feedback.manager.attachment.AttachmentGrantConsumePort
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeMediaPreviewCommand
import com.feedback.model.attachment.ConsumeOriginalLogAttachmentCommand
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 一次性附件安全流服务实现。
 *
 * grant 插入参与上层授权与审计短事务；消费事务仅覆盖权限复核、行锁和消费 CAS，文件与 S3 I/O 在提交后执行。
 */
@Service
class AttachmentSecureStreamServiceImpl(
    grantRepository: AttachmentGrantStorePort,
    provider: AttachmentContentProvider,
    grantConsumer: AttachmentGrantConsumePort,
    @Value("\${app.attachment.secure-stream.ttl-seconds:300}") ttlSeconds: Long
) : AttachmentSecureStreamService {
    private val operations = AttachmentSecureStreamOperations(
        grantRepository,
        provider,
        grantConsumer,
        ttlSeconds
    )

    /** 创建绑定主体、反馈、附件和用途的一次性令牌，只持久化 SHA-256 指纹。 */
    override fun issue(command: IssueAttachmentGrantCommand): AttachmentAccessGrant = operations.issue(command)

    /** 原子消费普通查看令牌后才打开内容源，避免数据库事务跨越文件或对象存储 I/O。 */
    override fun stream(command: ConsumeFeedbackAttachmentCommand): SecureAttachmentContent = operations.stream(command)

    /** 原子消费派生预览令牌后才打开私有预览对象。 */
    override fun preview(command: ConsumeMediaPreviewCommand): SecureAttachmentContent = operations.preview(command)

    /** 原子消费原始日志令牌后才打开原件，普通查看令牌不能互换。 */
    override fun original(command: ConsumeOriginalLogAttachmentCommand): SecureAttachmentContent = operations.original(command)

    /** 兼容内部调用：消费默认页面查看授权并打开原件。 */
    fun consume(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent =
        operations.consume(sourceId, attachmentId, token)

    /** 兼容内部调用：按默认上限生成页面安全日志视图。 */
    fun consumeForView(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent =
        operations.consumeForView(sourceId, attachmentId, token)

    /** 兼容内部调用：按经校验的上限生成页面安全日志视图。 */
    fun consumeForView(
        sourceId: Long,
        attachmentId: Long,
        token: String,
        previewLimitBytes: Long
    ): SecureAttachmentContent = operations.consumeForView(sourceId, attachmentId, token, previewLimitBytes)

    /** 兼容内部调用：消费原始日志用途令牌后打开原件。 */
    fun consumeOriginalLog(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent =
        operations.consumeOriginalLog(sourceId, attachmentId, token)

    /** 兼容内部调用：消费派生预览用途令牌后打开私有预览对象。 */
    fun consumeMediaPreview(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent =
        operations.consumeMediaPreview(sourceId, attachmentId, token)
}
