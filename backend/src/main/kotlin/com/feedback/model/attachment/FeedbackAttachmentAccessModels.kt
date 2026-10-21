package com.feedback.model.attachment

import com.feedback.common.AttachmentAuthorizationAction
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.AttachmentPreviewStatus
import com.feedback.common.AttachmentType
import com.feedback.security.WebPrincipal

/** 附件列表查询；只携带已经过 HTTP 基础校验的反馈标识。 */
data class FeedbackAttachmentListQuery(val sourceId: Long)

/** 单附件授权命令；用途使用封闭枚举防止令牌跨用途复用。 */
data class AuthorizeFeedbackAttachmentCommand(val sourceId: Long, val attachmentId: Long, val purpose: AttachmentGrantPurpose)

/** 批量预览授权命令；Service 负责去重并执行显式数量上限。 */
data class AuthorizeFeedbackAttachmentPreviewsCommand(val sourceId: Long, val attachmentIds: List<Long>)

/** 单个预览授权结果；不可用状态不得携带安全门失败细节。 */
data class AttachmentPreviewAuthorizationItem(val attachmentId: Long, val status: AttachmentPreviewStatus, val grant: AttachmentAccessGrant? = null)

/** 按请求顺序返回的有界批量预览授权结果。 */
data class AttachmentPreviewAuthorizationResponse(val items: List<AttachmentPreviewAuthorizationItem>)

/** 普通附件流消费命令；grantId 原文只在本次请求内存中传播。 */
data class ConsumeFeedbackAttachmentCommand(val sourceId: Long, val attachmentId: Long, val grantId: String, val previewLimitBytes: Long?)

/** 原始日志流消费命令；专用用途令牌不能降级为普通查看。 */
data class ConsumeOriginalLogAttachmentCommand(val sourceId: Long, val attachmentId: Long, val grantId: String)

/** 派生媒体预览消费命令；只允许读取已发布的私有预览对象。 */
data class ConsumeMediaPreviewCommand(val sourceId: Long, val attachmentId: Long, val grantId: String)

/** 已原子消费授权后的最小事实；事务提交后才能据此打开内容。 */
data class ConsumedAttachmentGrant(val attachmentType: AttachmentType?, val principal: WebPrincipal)

/** 授权签发命令；主体与用途在一次短事务内绑定。 */
data class IssueAttachmentGrantCommand(
    val sourceId: Long,
    val attachmentId: Long,
    val action: AttachmentAuthorizationAction,
    val principal: WebPrincipal,
    val purpose: AttachmentGrantPurpose,
)
