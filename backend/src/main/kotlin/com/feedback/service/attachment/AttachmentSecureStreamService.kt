package com.feedback.service.attachment

import com.feedback.manager.attachment.AttachmentGrantIssuePort
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeMediaPreviewCommand
import com.feedback.model.attachment.ConsumeOriginalLogAttachmentCommand
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import com.feedback.model.attachment.SecureAttachmentContent

/**
 * 一次性附件安全流服务。
 *
 * 签发由调用方的授权审计短事务包围；消费提交后才打开本地文件或对象流，避免长事务占用连接。
 */
interface AttachmentSecureStreamService : AttachmentGrantIssuePort {
    /** 创建指定用途的一次性授权；原始令牌只返回当前调用方。 */
    override fun issue(command: IssueAttachmentGrantCommand): AttachmentAccessGrant

    /** 消费普通查看授权并返回延迟内容源。 */
    fun stream(command: ConsumeFeedbackAttachmentCommand): SecureAttachmentContent

    /** 消费派生预览授权并返回私有预览内容源。 */
    fun preview(command: ConsumeMediaPreviewCommand): SecureAttachmentContent

    /** 消费原始日志专用授权并返回原件内容源。 */
    fun original(command: ConsumeOriginalLogAttachmentCommand): SecureAttachmentContent
}
