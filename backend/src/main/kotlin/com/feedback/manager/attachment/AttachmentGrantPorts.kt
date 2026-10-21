package com.feedback.manager.attachment

import com.feedback.common.AttachmentGrantPurpose
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.ConsumedAttachmentGrant
import com.feedback.model.attachment.IssueAttachmentGrantCommand

/** 一次性授权签发协作端口；实现只保存令牌指纹。 */
fun interface AttachmentGrantIssuePort {
    /** 创建指定用途的一次性授权；调用方负责同事务写授权审计。 */
    fun issue(command: IssueAttachmentGrantCommand): AttachmentAccessGrant
}

/** 原子消费协作端口；实现须在短事务内完成权限复核、行锁与消费 CAS。 */
fun interface AttachmentGrantConsumePort {
    /** 在短事务内复核并消费一次指定用途的授权。 */
    fun consume(sourceId: Long, attachmentId: Long, token: String, purpose: AttachmentGrantPurpose): ConsumedAttachmentGrant
}

/** Spring 短事务适配端口，避免内容打开逻辑持有框架事务对象。 */
fun interface AttachmentGrantTransactionPort {
    /** 在单个本地短事务中执行授权消费并返回不可变结果。 */
    fun execute(action: () -> ConsumedAttachmentGrant): ConsumedAttachmentGrant
}
