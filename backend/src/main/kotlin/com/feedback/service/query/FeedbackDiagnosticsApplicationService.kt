package com.feedback.service.query

import com.feedback.audit.AuditTextDigester
import com.feedback.common.AttachmentSecurityStatus
import com.feedback.common.FeedbackChannel
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditEvent
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditPort
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsReadPort
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.security.FeedbackAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 反馈诊断按权限查看用例。
 *
 * 本用例通过公共边界先验证反馈 SSO 主体资格，再完成反馈存在性、生产环境、脱敏状态及数据范围校验；
 * 该纵深防御不替代公共路由鉴权。身份与 SDK 密文必须具备完整 AES-256-GCM 材料且使用当前 key ID，
 * SDK 明文还必须是 JSON object。所有事实完整读取、校验、解密和组装后才按角色呈现：具备原文权限
 * 的技术角色得到 RAW，其余产品角色得到 PARTIALLY_HIDDEN；日志始终只返回受治理元数据。
 *
 * 诊断审计记录固定查看动作、类型和可见性，不接收或保存用户填写的查看理由。
 * 审计与读取处于同一事务，任何读取、解密、解析、展示或审计失败都不会返回部分敏感响应。
 */
@Service
class FeedbackDiagnosticsApplicationService(
    private val accessGuard: FeedbackAccessGuard,
    private val reads: FeedbackDiagnosticsReadPort,
    private val audits: FeedbackDiagnosticsAuditPort,
    private val principalProvider: CurrentWebPrincipalProvider,
    private val decoder: FeedbackDiagnosticValueDecoder
) : FeedbackDiagnosticsQueryUseCase {
    /** GET 与兼容 POST 均使用固定动作码；页面不提供查看理由输入。 */
    @Transactional
    override fun viewDirect(sourceId: Long, ipAddress: String?): FeedbackDiagnosticsResponse =
        viewWithinTransaction(sourceId, ipAddress)

    private fun viewWithinTransaction(
        sourceId: Long,
        ipAddress: String?
    ): FeedbackDiagnosticsResponse {
        val sourceChannel = accessGuard.require(sourceId).facts.sourceChannel
        val principal = principalProvider.require()
        val purposeDigest = AuditTextDigester.digest(DIRECT_VIEW_PURPOSE)
        val values = mutableListOf<FeedbackDiagnosticItemResponse>()
        values += decoder.identities(reads.identities(sourceId))
        if (FeedbackChannel.fromStorage(sourceChannel) == FeedbackChannel.STATION_FEEDBACK) {
            values += decoder.station(reads.station(sourceId))
        }
        if (FeedbackChannel.fromStorage(sourceChannel) == FeedbackChannel.SDK) values += decoder.sdk(reads.sdk(sourceId))
        reads.attachments(sourceId).forEach { row ->
            val available = AttachmentSecurityStatus.fromStorage(row.securityStatus) == AttachmentSecurityStatus.VERIFIED &&
                row.integrityStatus == "VERIFIED" &&
                row.malwareScanStatus == "CLEAN" && row.contentReviewStatus == "PASS"
            values += FeedbackDiagnosticItemResponse(
                "APPLICATION_LOG",
                "日志附件 ${row.sourceAttachmentId ?: row.id} · ${row.mimeType ?: "未知类型"} · ${row.fileSizeBytes ?: 0} 字节 · " +
                    if (available) "安全检查已通过，可直接查看" else "安全检查中，暂不可读取内容",
                "GOVERNED_ATTACHMENT", row.id, row.sourceAttachmentId, row.fileSizeBytes, row.mimeType, available,
                row.securityStatus, row.integrityStatus, row.malwareScanStatus, row.contentReviewStatus
            )
        }
        if (values.isEmpty()) throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_DIAGNOSTICS_NOT_AVAILABLE")
        val presented = decoder.present(
            decoder.distinctSemanticValues(values.filter { it.source != "GOVERNED_ATTACHMENT" }),
            principal
        ) +
            values.filter { it.source == "GOVERNED_ATTACHMENT" }
        val visibility = decoder.visibility(principal)
        audits.append(
            FeedbackDiagnosticsAuditEvent(
                actorId = principal.userId,
                sourceId = sourceId,
                purposeCode = "DIRECT_VIEW",
                purposeSha256 = purposeDigest.sha256,
                purposeLength = purposeDigest.length,
                types = values.map { it.type }.distinct(),
                visibility = visibility,
                ipAddress = ipAddress
            )
        )
        return FeedbackDiagnosticsResponse(sourceId, presented, visibility)
    }

    private companion object {
        const val DIRECT_VIEW_PURPOSE = "反馈直接查看"
    }
}
