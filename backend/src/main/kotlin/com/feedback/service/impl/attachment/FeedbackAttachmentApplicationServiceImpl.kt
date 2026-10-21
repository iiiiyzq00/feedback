package com.feedback.service.impl.attachment

import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditEvent
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditPort
import com.feedback.application.port.feedback.attachment.AttachmentMediaPreviewPriorityPort
import com.feedback.application.port.feedback.attachment.FeedbackAttachmentReadPort
import com.feedback.audit.AuditTextDigester
import com.feedback.common.AttachmentAuditOperation
import com.feedback.common.AttachmentAuthorizationAction
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.AttachmentPreviewStatus
import com.feedback.common.AttachmentType
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import com.feedback.manager.attachment.AttachmentGrantIssuePort
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentPreviewAuthorizationItem
import com.feedback.model.attachment.AttachmentPreviewAuthorizationResponse
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentPreviewsCommand
import com.feedback.model.attachment.FeedbackAttachmentListQuery
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import com.feedback.model.auth.RoleCode
import com.feedback.security.ContentVisibility
import com.feedback.security.attachment.FeedbackAttachmentAccessPort
import com.feedback.service.attachment.FeedbackAttachmentApplicationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 反馈附件列表与一次性授权实现。
 * 列表只返回安全摘要；授权在短事务内复核事实、签发 grant 并共同写入审计。
 */
@Service
class FeedbackAttachmentApplicationServiceImpl(
    private val access: FeedbackAttachmentAccessPort,
    private val attachments: FeedbackAttachmentReadPort,
    private val audits: AttachmentAuthorizationAuditPort,
    private val grants: AttachmentGrantIssuePort,
    private val previewPriority: AttachmentMediaPreviewPriorityPort,
) : FeedbackAttachmentApplicationService {
    /** 固定一次字段策略快照并返回非日志附件安全摘要。 */
    override fun list(query: FeedbackAttachmentListQuery): List<FeedbackAttachmentSummaryResponse> {
        access.requireScope(query.sourceId)
        val fieldPolicy = access.fieldPolicy()
        return attachments.findListFacts(query.sourceId).map { FeedbackAttachmentSummaryResponse.from(it, fieldPolicy) }
    }

    /** 在单个短写事务中按封闭用途分派授权，事务内不打开文件或对象流。 */
    @Transactional
    override fun authorize(command: AuthorizeFeedbackAttachmentCommand): AttachmentAccessGrant = when (command.purpose) {
        AttachmentGrantPurpose.INLINE_VIEW -> authorizeView(command.sourceId, command.attachmentId)
        AttachmentGrantPurpose.DERIVED_PREVIEW -> authorizePreview(command.sourceId, command.attachmentId)
        AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD -> authorizeOriginalLogDownload(command.sourceId, command.attachmentId)
    }

    /** 批量复核最多二十个附件，一次加载事实并为可用项分别签发授权。 */
    @Transactional
    override fun authorizePreviews(command: AuthorizeFeedbackAttachmentPreviewsCommand): AttachmentPreviewAuthorizationResponse {
        access.requireScope(command.sourceId)
        val ids = command.attachmentIds.distinct()
        if (ids.isEmpty() || ids.size > MAX_BATCH_PREVIEWS || ids.any { it <= 0 }) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_PREVIEW_BATCH_INVALID")
        }
        val pending = mutableListOf<Long>()
        val principal = access.principal()
        val reasonDigest = AuditTextDigester.digest(AttachmentAuthorizationAction.DERIVED_PREVIEW.code)
        val factsById = attachments.findAuthorizationFacts(ids, command.sourceId).associateBy { it.id }
        val items = ids.map { attachmentId ->
            val attachment = factsById[attachmentId]
            when {
                attachment == null || !attachment.isViewable() || attachment.attachmentType !in MEDIA_TYPES ->
                    AttachmentPreviewAuthorizationItem(attachmentId, AttachmentPreviewStatus.UNAVAILABLE)
                attachment.mediaPreviewStatus == AttachmentPreviewStatus.AVAILABLE -> {
                    val grant = grants.issue(IssueAttachmentGrantCommand(
                        command.sourceId,
                        attachmentId,
                        AttachmentAuthorizationAction.DERIVED_PREVIEW,
                        principal,
                        AttachmentGrantPurpose.DERIVED_PREVIEW,
                    ))
                    audits.append(AttachmentAuthorizationAuditEvent(
                        principal.userId,
                        attachmentId,
                        command.sourceId,
                        AttachmentAuthorizationAction.DERIVED_PREVIEW,
                        reasonDigest.sha256,
                        reasonDigest.length,
                        true,
                        grant.expiresInSeconds,
                        AttachmentAuditOperation.ATTACHMENT_PREVIEW,
                    ))
                    AttachmentPreviewAuthorizationItem(attachmentId, AttachmentPreviewStatus.AVAILABLE, grant)
                }
                attachment.mediaPreviewStatus in PENDING_PREVIEW_STATUSES -> {
                    pending += attachmentId
                    AttachmentPreviewAuthorizationItem(attachmentId, AttachmentPreviewStatus.PENDING)
                }
                else -> AttachmentPreviewAuthorizationItem(attachmentId, AttachmentPreviewStatus.UNAVAILABLE)
            }
        }
        previewPriority.prioritize(command.sourceId, pending)
        return AttachmentPreviewAuthorizationResponse(items)
    }

    /** 复核派生对象已发布且媒体类型有效，然后共同写入授权和审计。 */
    private fun authorizePreview(sourceId: Long, attachmentId: Long): AttachmentAccessGrant {
        access.requireScope(sourceId)
        val attachment = attachments.findAuthorizationFacts(attachmentId, sourceId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_FOUND")
        if (!attachment.isViewable() || attachment.mediaPreviewStatus != AttachmentPreviewStatus.AVAILABLE) {
            throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_PREVIEW_NOT_AVAILABLE")
        }
        if (attachment.attachmentType !in MEDIA_TYPES) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_PREVIEW_MEDIA_REQUIRED")
        }
        return issueAndAudit(sourceId, attachmentId, AttachmentAuthorizationAction.DERIVED_PREVIEW, AttachmentGrantPurpose.DERIVED_PREVIEW, AttachmentAuditOperation.ATTACHMENT_PREVIEW)
    }

    /** 复核普通附件实时安全状态，然后共同写入授权与解密审计。 */
    private fun authorizeView(sourceId: Long, attachmentId: Long): AttachmentAccessGrant {
        access.requireScope(sourceId)
        val attachment = attachments.findAuthorizationFacts(attachmentId, sourceId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_FOUND")
        if (!attachment.isViewable()) {
            throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE")
        }
        return issueAndAudit(sourceId, attachmentId, AttachmentAuthorizationAction.DIRECT_VIEW, AttachmentGrantPurpose.INLINE_VIEW, AttachmentAuditOperation.ATTACHMENT_DECRYPT)
    }

    /** 复核 RAW 权限、日志类型与安全状态，然后签发原件下载专用授权。 */
    private fun authorizeOriginalLogDownload(sourceId: Long, attachmentId: Long): AttachmentAccessGrant {
        access.requireScope(sourceId)
        val attachment = attachments.findAuthorizationFacts(attachmentId, sourceId)
            ?: throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_FOUND")
        val principal = access.principal()
        if (RoleCode.ADMIN !in principal.roles && !ContentVisibility.canViewRaw(principal)) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "ORIGINAL_LOG_DOWNLOAD_RAW_PERMISSION_REQUIRED")
        }
        if (attachment.attachmentType != AttachmentType.LOG) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ORIGINAL_LOG_DOWNLOAD_LOG_REQUIRED")
        }
        if (!attachment.isViewable()) {
            throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE")
        }
        return issueAndAudit(sourceId, attachmentId, AttachmentAuthorizationAction.ORIGINAL_LOG_DOWNLOAD, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD, AttachmentAuditOperation.ORIGINAL_LOG_DOWNLOAD)
    }

    /** 在当前授权事务内签发用途绑定 grant 并写入不可含敏感原文的审计摘要。 */
    private fun issueAndAudit(
        sourceId: Long,
        attachmentId: Long,
        action: AttachmentAuthorizationAction,
        purpose: AttachmentGrantPurpose,
        operation: AttachmentAuditOperation,
    ): AttachmentAccessGrant {
        val principal = access.principal()
        val reasonDigest = AuditTextDigester.digest(action.code)
        val grant = grants.issue(IssueAttachmentGrantCommand(sourceId, attachmentId, action, principal, purpose))
        audits.append(AttachmentAuthorizationAuditEvent(
            principal.userId,
            attachmentId,
            sourceId,
            action,
            reasonDigest.sha256,
            reasonDigest.length,
            true,
            grant.expiresInSeconds,
            operation,
        ))
        return grant
    }

    companion object {
        /** 单次批量预览授权上限，防止无界集合和过长写事务。 */
        const val MAX_BATCH_PREVIEWS = 20
        private val PENDING_PREVIEW_STATUSES = setOf(AttachmentPreviewStatus.PENDING, AttachmentPreviewStatus.PROCESSING, AttachmentPreviewStatus.FAILED)
        private val MEDIA_TYPES = setOf(AttachmentType.IMAGE, AttachmentType.SCREENSHOT, AttachmentType.ANNOTATED_SCREENSHOT, AttachmentType.VIDEO, AttachmentType.SCREEN_RECORDING)
    }
}
