package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentPreviewsCommand
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeMediaPreviewCommand
import com.feedback.model.attachment.ConsumeOriginalLogAttachmentCommand
import com.feedback.model.attachment.FeedbackAttachmentListQuery
import com.feedback.model.attachment.SecureAttachmentContent
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.feedback.service.attachment.FeedbackAttachmentApplicationService
import com.feedback.service.query.FeedbackDiagnosticsQueryUseCase
import com.feedback.service.query.FeedbackDiagnosticsResponse
import com.feedback.service.query.RelatedFeedbackPageResponse
import com.feedback.service.query.RelatedFeedbackQueryUseCase
import com.feedback.service.query.detail.FeedbackDetailQueryUseCase
import com.feedback.service.query.detail.FeedbackExternalLinkQueryUseCase
import com.feedback.service.query.detail.FeedbackModificationQueryUseCase
import com.feedback.service.query.detail.FeedbackTimelineQueryUseCase
import com.feedback.service.query.listprojection.FeedbackExportAuditContext
import com.feedback.service.query.listprojection.FeedbackExportUseCase
import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 反馈列表 HTTP 入口；只依赖稳定查询契约。 */
@Validated
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackListController(private val listReads: FeedbackListQueryUseCase) {
    /** 返回当前查询条件下的安全 ACTIVE 投影。 */
    @Operation(summary = "查询反馈渠道反馈列表", description = "只读取校验完整的唯一 ACTIVE 安全投影；开关关闭或 generation 不可用时返回 503，绝不回退旧 SQL。")
    @GetMapping
    fun list(@Valid @ModelAttribute request: FeedbackListRequest) = ApiResponse.success(listReads.list(request.toQuery()))
}

/** 反馈详情 HTTP 入口；权限与字段裁剪由查询 Service 统一完成。 */
@Validated
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackDetailController(
    private val details: FeedbackDetailQueryUseCase,
    private val modifications: FeedbackModificationQueryUseCase,
    private val timelines: FeedbackTimelineQueryUseCase,
    private val externalLinks: FeedbackExternalLinkQueryUseCase,
) {
    /** 返回单条反馈的授权详情。 */
    @Operation(summary = "查看反馈详情", description = "按当前用户的渠道、模块、区域和字段权限返回脱敏后的聚合详情，并记录查看审计。")
    @GetMapping("/{id}")
    fun detail(@PathVariable @Positive id: Long) = ApiResponse.success(details.detail(id))

    /** 返回具名字段转换历史，不包含未授权字段。 */
    @Operation(summary = "查看反馈字段转换历史", description = "按当前反馈数据范围返回具名字段转换记录，不包含未授权字段。")
    @GetMapping("/{id}/modifications")
    fun modifications(@PathVariable @Positive id: Long) = ApiResponse.success(modifications.modifications(id))

    /** 返回导入、脱敏、AI、归簇与字段变更安全事件。 */
    @Operation(summary = "查看反馈详情时间轴", description = "在反馈数据范围校验通过后返回导入、脱敏、AI、归簇及字段变更的具名安全事件。")
    @GetMapping("/{id}/timeline")
    fun timeline(@PathVariable @Positive id: Long) = ApiResponse.success(timelines.timeline(id))

    /** 返回经过 HTTPS 结构校验的来源系统只读外链。 */
    @Operation(summary = "获取来源系统只读外链", description = "仅对获授权角色返回通过 HTTPS URI 结构校验的 Jira 或红黑榜只读地址，并在成功返回前记录查看审计；当前不表示已实施主机白名单。")
    @GetMapping("/{id}/external-link")
    fun externalLink(@PathVariable @Positive id: Long) = ApiResponse.success(externalLinks.externalLink(id))
}

/** 反馈关联项与诊断 HTTP 入口；诊断响应统一禁止缓存。 */
@Validated
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackController(
    private val relatedReads: RelatedFeedbackQueryUseCase,
    private val diagnosticsReads: FeedbackDiagnosticsQueryUseCase,
) {
    /** 返回同一安全 generation 内的有界关联反馈页。 */
    @Operation(summary = "查看同簇反馈", description = "在同一完整 ACTIVE generation 中读取成员投影和安全正文投影；当前反馈尚未归簇时返回空页，双开关关闭时返回 503。")
    @GetMapping("/{id}/related")
    fun related(
        @PathVariable @Positive id: Long,
        @RequestParam(defaultValue = "1") @Positive page: Int,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) size: Int,
    ): ApiResponse<RelatedFeedbackPageResponse> = ApiResponse.success(relatedReads.related(id, page, size))

    /** 按当前权限返回诊断信息并禁止客户端缓存。 */
    @Operation(summary = "按权限查看反馈诊断", description = "无需填写理由；技术角色查看原文、产品角色局部隐藏，响应及审计任一失败均不返回部分敏感结果。")
    @GetMapping("/{id}/diagnostics")
    fun viewDiagnostics(@PathVariable @Positive id: Long, http: HttpServletRequest): ResponseEntity<ApiResponse<FeedbackDiagnosticsResponse>> =
        noStore(diagnosticsReads.viewDirect(id, http.remoteAddr))

    /** 保留旧 POST 诊断入口并执行与 GET 相同的安全语义。 */
    @Operation(summary = "兼容反馈诊断查看入口", description = "无需请求体或查看理由；按当前角色权限直接展示。")
    @PostMapping("/{id}/diagnostics/authorize")
    fun authorizeDiagnostics(@PathVariable @Positive id: Long, http: HttpServletRequest): ResponseEntity<ApiResponse<FeedbackDiagnosticsResponse>> =
        noStore(diagnosticsReads.viewDirect(id, http.remoteAddr))

    /** 包装禁止缓存的安全诊断响应。 */
    private fun <T> noStore(value: T): ResponseEntity<ApiResponse<T>> = ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("Pragma", "no-cache")
        .body(ApiResponse.success(value))
}

/** 反馈导出 HTTP 入口；审计上下文在入口统一生成。 */
@Validated
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackExportController(private val exports: FeedbackExportUseCase) {
    /** 按筛选条件或显式 ID 集合导出有界安全投影。 */
    @Operation(summary = "按反馈列表条件导出", description = "最多导出 5000 条安全投影记录；返回前逐行通过出站隐私门禁并成功写入审计队列。")
    @GetMapping("/export")
    fun export(@Valid @ModelAttribute request: FeedbackExportQueryRequest, http: HttpServletRequest): ResponseEntity<String> =
        csv(if (request.ids.isNotEmpty()) exports.exportSelection(request.ids, auditContext(http)) else exports.exportQuery(request.toQuery(), auditContext(http)))

    /** 按最多五千个显式反馈 ID 导出，任一越权或缺失则整体拒绝。 */
    @Operation(summary = "按反馈 ID 导出", description = "最多接收 5000 个 ID，服务端按 200 个一批读取安全投影；任一 ID 越权或缺失时整体拒绝。")
    @PostMapping("/export")
    fun exportSelection(@Valid @RequestBody request: FeedbackSelectionRequest, http: HttpServletRequest): ResponseEntity<String> =
        csv(exports.exportSelection(request.ids, auditContext(http)))

    /** 生成兼容 Excel 的 UTF-8 BOM CSV 下载响应。 */
    private fun csv(body: String): ResponseEntity<String> = ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=feedback.csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body("\uFEFF$body")

    /** 从请求取得追踪 ID；缺失时生成新的稳定 UUID。 */
    private fun auditContext(request: HttpServletRequest) = FeedbackExportAuditContext(
        request.getAttribute("requestId")?.toString() ?: UUID.randomUUID().toString(),
        request.remoteAddr,
    )
}

/** 批量预览授权请求；HTTP 校验与 Service 数量上限形成双重保护。 */
data class BatchAttachmentPreviewAuthorizationRequest(
    @field:Size(min = 1, max = 20)
    val attachmentIds: List<@Positive Long>,
)

/** 附件摘要、授权与安全流 HTTP 入口；只依赖两个唯一 Service 契约。 */
@Validated
@RestController
@RequestMapping("/api/v1/feedback")
class FeedbackAttachmentController(
    private val attachments: FeedbackAttachmentApplicationService,
    private val streams: AttachmentSecureStreamService,
) {
    /** 返回非日志附件安全摘要，列表快照不替代实时授权。 */
    @Operation(summary = "查看非日志附件摘要", description = "数据范围校验通过后返回按创建时间和 ID 稳定排序的安全元数据；空结果返回空列表。不包含存储路径、附件正文或访问令牌；available 是查询时刻快照，真实查看仍需实时授权和安全复核。")
    @GetMapping("/{id}/attachments")
    fun attachments(@PathVariable @Positive id: Long) = ApiResponse.success(attachments.list(FeedbackAttachmentListQuery(id)))

    /** 为普通查看签发一次性、同源且用途绑定的授权。 */
    @Operation(summary = "按权限获取附件查看地址", description = "无需请求体或查看理由；实时复核当前角色的数据范围、附件归属及全部安全状态，再返回 30 至 300 秒的一次性同源流地址。")
    @PostMapping("/{id}/attachments/{attachmentId}/authorize")
    fun authorizeAttachment(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long) =
        ApiResponse.success(attachments.authorize(AuthorizeFeedbackAttachmentCommand(id, attachmentId, AttachmentGrantPurpose.INLINE_VIEW)))

    /** 为已发布媒体派生预览签发专用一次性授权。 */
    @Operation(summary = "申请媒体派生预览授权", description = "实时复核数据范围、附件归属、安全状态和派生对象状态，返回仅能消费私有 JPEG 预览的一次性同源地址。")
    @PostMapping("/{id}/attachments/{attachmentId}/authorize-preview")
    fun authorizeAttachmentPreview(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long) =
        ApiResponse.success(attachments.authorize(AuthorizeFeedbackAttachmentCommand(id, attachmentId, AttachmentGrantPurpose.DERIVED_PREVIEW)))

    /** 批量签发最多二十个独立预览授权并禁止缓存结果。 */
    @Operation(summary = "批量申请可见媒体预览授权", description = "最多 20 个附件；可用项各自返回不可互换的一次性 grant，待生成项只异步提升优先级，不在请求线程读取原件。")
    @PostMapping("/{id}/attachments/authorize-previews")
    fun authorizeAttachmentPreviews(@PathVariable @Positive id: Long, @Valid @RequestBody request: BatchAttachmentPreviewAuthorizationRequest) =
        ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(ApiResponse.success(attachments.authorizePreviews(AuthorizeFeedbackAttachmentPreviewsCommand(id, request.attachmentIds))))

    /** 为原始日志下载签发不可与页面查看互换的授权。 */
    @Operation(summary = "申请原始日志一次性下载授权", description = "仅 ADMIN 或具备 RAW 原文权限的技术角色可用；实时复核附件安全状态，签发与页面查看不可互换的一次性用途令牌并记录 ORIGINAL_LOG_DOWNLOAD 审计。")
    @PostMapping("/{id}/attachments/{attachmentId}/authorize-original-download")
    fun authorizeOriginalLogDownload(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long) =
        ApiResponse.success(attachments.authorize(AuthorizeFeedbackAttachmentCommand(id, attachmentId, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)))

    /** 消费普通查看授权并返回禁止缓存的延迟安全流。 */
    @Operation(summary = "消费附件一次性安全流", description = "令牌绑定当前用户、反馈和附件并且只能消费一次；并发、过期、撤销或已消费令牌均拒绝，响应禁止缓存且不暴露存储路径。")
    @GetMapping("/{id}/attachments/{attachmentId}/stream")
    fun streamAttachment(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long, @RequestParam @Size(max = 200) grantId: String, @RequestParam(required = false) @Min(1024) @Max(5_242_880) previewBytes: Long?) =
        response(streams.stream(ConsumeFeedbackAttachmentCommand(id, attachmentId, grantId, previewBytes)), true)

    /** 消费媒体预览专用授权并返回私有派生对象流。 */
    @Operation(summary = "消费媒体派生预览一次性安全流", description = "令牌只绑定派生预览用途；响应禁止缓存，不暴露私有对象键或永久地址。")
    @GetMapping("/{id}/attachments/{attachmentId}/preview")
    fun streamAttachmentPreview(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long, @RequestParam @Size(max = 200) grantId: String) =
        response(streams.preview(ConsumeMediaPreviewCommand(id, attachmentId, grantId)), true)

    /** 消费原始日志专用授权并返回未转换原件。 */
    @Operation(summary = "消费原始日志一次性下载流", description = "用途令牌绑定当前用户、反馈和日志附件且仅可消费一次；返回未解压、未截断、未转码的原始日志或原始压缩包。")
    @GetMapping("/{id}/attachments/{attachmentId}/original")
    fun downloadOriginalLog(@PathVariable @Positive id: Long, @PathVariable @Positive attachmentId: Long, @RequestParam @Size(max = 200) grantId: String) =
        response(streams.original(ConsumeOriginalLogAttachmentCommand(id, attachmentId, grantId)), false)

    /** 在授权已提交后组装安全响应头并延迟打开底层内容。 */
    private fun response(content: SecureAttachmentContent, inline: Boolean): ResponseEntity<Resource> {
        val disposition = if (inline) ContentDisposition.inline() else ContentDisposition.attachment()
        val response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.mimeType))
            .header("Content-Disposition", disposition.filename(safeFilename(content.filename), StandardCharsets.UTF_8).build().toString())
            .header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
        content.contentLength?.let(response::contentLength)
        return response.body(InputStreamResource(content::openStream))
    }

    /** 去除目录和响应头危险字符，并把空文件名收敛为安全默认值。 */
    private fun safeFilename(value: String): String {
        val leaf = value.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.filter { it in ' '..'~' && it !in setOf('"', '\'', ';') }.trim().take(120)
        return cleaned.ifBlank { "attachment.bin" }
    }
}
