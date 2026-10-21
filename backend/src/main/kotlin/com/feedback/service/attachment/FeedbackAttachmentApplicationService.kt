package com.feedback.service.attachment

import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentPreviewAuthorizationResponse
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentPreviewsCommand
import com.feedback.model.attachment.FeedbackAttachmentListQuery

/**
 * 反馈附件查询与授权应用服务。
 *
 * 列表只返回安全摘要；授权必须在一个短写事务中完成实时事实复核、一次性 grant 创建和审计写入。
 */
interface FeedbackAttachmentApplicationService {
    /** 校验反馈范围后返回经过字段策略裁剪的附件摘要。 */
    fun list(query: FeedbackAttachmentListQuery): List<FeedbackAttachmentSummaryResponse>

    /** 实时复核附件状态并原子创建一次性授权与审计。 */
    fun authorize(command: AuthorizeFeedbackAttachmentCommand): AttachmentAccessGrant

    /** 在固定上限内批量授权可用预览并提升待处理预览优先级。 */
    fun authorizePreviews(command: AuthorizeFeedbackAttachmentPreviewsCommand): AttachmentPreviewAuthorizationResponse
}
