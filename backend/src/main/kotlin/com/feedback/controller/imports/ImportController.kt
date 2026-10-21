package com.feedback.controller.imports

import com.feedback.common.ApiResponse
import com.feedback.service.imports.*
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ContentDisposition
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.Authentication
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDate
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import com.feedback.service.jira.Jira400SyncService
import com.feedback.service.jira.JiraSyncEnqueueResult
import com.feedback.service.jira.JiraFormalReviewResult
import com.feedback.service.jira.JiraLogAttachmentImportService
import com.feedback.service.jira.JiraLogAttachmentImportResult
import com.feedback.service.jira.JiraAttachmentPullQueue
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/v1/import")
class ImportController(
    private val service: ImportBatchService,
    private val templates: ImportTemplateService,
    private val chunks: ImportChunkUploadService,
    private val reconciliationExports: ImportReconciliationExportService,
    private val attachmentUploads: ImportAttachmentUploadService,
    private val batchGovernance:ImportBatchGovernanceService?=null,
    private val dataQuality:UnifiedDataQualityService?=null,
    private val jiraSync:Jira400SyncService?=null,
    private val jiraLogAttachments:JiraLogAttachmentImportService?=null,
    private val jiraAttachmentQueue:JiraAttachmentPullQueue?=null
) {
    @GetMapping("/templates")
    fun templates() = ApiResponse.success(templates.activeTemplates())

    @GetMapping("/templates/{channel}/download", produces = ["text/csv"])
    fun downloadTemplate(@PathVariable channel: String): ResponseEntity<ByteArray> {
        val normalized = channel.trim().uppercase()
        ensureImport(normalized in setOf("SDK", "JIRA", "QUALITY", "REDBLACK")) { "IMPORT_CHANNEL_INVALID" }
        val (filename, body) = templates.csv(normalized)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(body)
    }

    @PostMapping("/upload")
    fun upload(@RequestParam file: MultipartFile, @RequestParam("source_channel") channel: String,
               @RequestParam("sheet_name", required = false) sheet: String?,
               @RequestParam("data_date_from",required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) dataDateFrom:LocalDate?=null,
               @RequestParam("data_date_to",required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) dataDateTo:LocalDate?=null,auth:Authentication?=null): ResponseEntity<ApiResponse<ImportUploadResult>> {
        requireSdkAdmin(channel,auth)
        ensureImport(file.size<=5L*1024*1024) { "IMPORT_LARGE_FILE_REQUIRES_ENCRYPTED_CHUNK_STAGING" }
        val result=if(dataDateFrom==null&&dataDateTo==null)service.upload(file,channel,sheet) else service.upload(file,channel,sheet,dataDateFrom,dataDateTo)
        return ResponseEntity.accepted().body(ApiResponse.success(result))
    }

    @PostMapping("/upload/chunk")
    fun uploadChunk(
        @RequestParam("file_id") fileId: String,
        @RequestParam("source_channel") channel: String,
        @RequestParam("chunk_index") chunkIndex: Int,
        @RequestParam("total_chunks") totalChunks: Int,
        @RequestParam("chunk_sha256") chunkSha256: String,
        @RequestParam("chunk_data") data: MultipartFile,auth:Authentication?=null
    ):ApiResponse<ImportChunkStatus> { requireSdkAdmin(channel,auth);return ApiResponse.success(chunks.put(fileId, channel, chunkIndex, totalChunks, chunkSha256, data)) }

    @GetMapping("/upload/chunk/{fileId}")
    fun chunkStatus(@PathVariable fileId: String) = ApiResponse.success(chunks.status(fileId))

    @PostMapping("/upload/chunk/{fileId}/complete")
    fun completeChunks(@PathVariable fileId: String, @Valid @RequestBody request: CompleteChunkUploadRequest,
                       auth:Authentication?=null):ResponseEntity<ApiResponse<ImportChunkStatus>> {
        requireSdkAdmin(chunks.channelOf(fileId), auth)
        return ResponseEntity.accepted().body(ApiResponse.success(chunks.complete(fileId, request.filename, request.fullSha256, request.sheetName,request.dataDateFrom,request.dataDateTo)))
    }

    @PostMapping("/attachments/{taskId}")
    fun uploadAttachment(
        @PathVariable taskId: Long,
        @RequestParam("sha256") sha256: String,
        @RequestParam("mime_type") mimeType: String,
        @RequestParam("encrypted_data_key") encryptedDataKey: String,
        @RequestParam("algorithm_version") algorithmVersion: String,
        @RequestParam("encrypted_file") encryptedFile: MultipartFile
    ) = ApiResponse.success(attachmentUploads.upload(taskId, sha256, mimeType, encryptedDataKey, algorithmVersion, encryptedFile))

    @GetMapping("/batches") fun batches(@RequestParam(defaultValue="1")page:Int,@RequestParam(defaultValue="20")size:Int,
        @RequestParam(required=false)channel:String?,@RequestParam(required=false)status:String?,
        @RequestParam(defaultValue="false")includeGoverned:Boolean) = ApiResponse.success(service.list(page,size,channel,status,includeGoverned))
    @PostMapping("/batches/governance/cleanup")
    fun cleanupBatches(@Valid @RequestBody request:ImportBatchCleanupRequest,auth:Authentication)=
        ApiResponse.success(importRequired(batchGovernance){"IMPORT_BATCH_GOVERNANCE_NOT_CONFIGURED"}
            .cleanup((auth.principal as WebPrincipal).userId,request.reason))
    @GetMapping("/quality/channels") fun channelQuality()=ApiResponse.success(dataQuality?.latest().orEmpty())
    @PostMapping("/quality/channels/refresh") fun refreshChannelQuality()=ApiResponse.success(dataQuality?.refreshChannels().orEmpty())
    @GetMapping("/batches/{batchId}") fun detail(@PathVariable batchId: String) = ApiResponse.success(service.detail(batchId))
    @GetMapping("/batches/{batchId}/mapping-preview") fun mappingPreview(@PathVariable batchId:String) = ApiResponse.success(service.mappingPreview(batchId))

    @GetMapping("/jira/sync/status")
    fun jiraSyncStatus() = ApiResponse.success(importRequired(jiraSync) { "JIRA_SYNC_SERVICE_NOT_CONFIGURED" }.status())

    @PostMapping("/jira/sync")
    fun enqueueJiraSync(@Valid @RequestBody(required=false) request:JiraSyncRequest?,auth:Authentication?):ResponseEntity<ApiResponse<JiraSyncEnqueueResult>> {
        val actor=(auth?.principal as? WebPrincipal)?.userId
        val input=request?:JiraSyncRequest()
        val trigger=if(input.from!=null||input.to!=null)"BACKFILL" else "MANUAL"
        val result=importRequired(jiraSync){"JIRA_SYNC_SERVICE_NOT_CONFIGURED"}.enqueue(trigger,input.from,input.to,actor)
        return ResponseEntity.status(if(result.accepted)202 else 409).body(ApiResponse.success(result))
    }

    @PostMapping("/jira/formal-review/re-pull")
    fun rePullJiraFormalReview(@Valid @RequestBody request:JiraFormalReviewRequest,auth:Authentication?):ResponseEntity<ApiResponse<JiraFormalReviewResult>> {
        val actor=(auth?.principal as? WebPrincipal)?.userId
        val result=importRequired(jiraSync){"JIRA_SYNC_SERVICE_NOT_CONFIGURED"}.rePullForFormalReview(request.issueKeys,actor)
        return ResponseEntity.status(if(result.accepted)202 else 409).body(ApiResponse.success(result))
    }

    @PostMapping("/jira/log-attachments/pull")
    fun pullJiraLogAttachments(@Valid @RequestBody request:JiraLogAttachmentRequest):ApiResponse<JiraLogAttachmentImportResult> =
        ApiResponse.success(importRequired(jiraLogAttachments){"JIRA_LOG_ATTACHMENT_SERVICE_NOT_CONFIGURED"}.pull(request.issueKeys))

    @PostMapping("/jira/attachment-tasks/retry")
    fun retryJiraAttachmentTasks():ApiResponse<Map<String,Int>> = ApiResponse.success(mapOf(
        "retried" to importRequired(jiraAttachmentQueue){"JIRA_ATTACHMENT_QUEUE_NOT_CONFIGURED"}.retryFailed()))

    @PostMapping("/jira/fault-time/re-pull")
    fun rePullJiraFaultTime(@Valid @RequestBody request:JiraFormalReviewRequest,auth:Authentication?):ResponseEntity<ApiResponse<JiraFormalReviewResult>> {
        val actor=(auth?.principal as? WebPrincipal)?.userId
        val result=importRequired(jiraSync){"JIRA_SYNC_SERVICE_NOT_CONFIGURED"}.rePullForFaultTimeRepair(request.issueKeys,actor)
        return ResponseEntity.status(if(result.accepted)202 else 409).body(ApiResponse.success(result))
    }

    @GetMapping("/batches/{batchId}/errors",produces=["text/csv;charset=UTF-8"])
    fun errorReport(@PathVariable batchId:String):ResponseEntity<ByteArray>{
        fun cell(value:Any?):String="\"${value?.toString().orEmpty().replace("\"","\"\"").take(500)}\""
        val detail=service.detail(batchId)
        val rows=buildList{add("row_number,status,error_code,error_summary,suggestion");detail.errors.forEach{add(listOf(it.rowNumber,it.status,it.errorCode,it.summary,it.suggestion).joinToString(",",transform=::cell))}}
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("${batchId}_safe_errors.csv").build().toString())
            .header("X-Content-Type-Options","nosniff").body(("\uFEFF"+rows.joinToString("\r\n")+"\r\n").toByteArray())
    }

    @GetMapping("/batches/{batchId}/export", produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"])
    fun export(@PathVariable batchId: String): ResponseEntity<ByteArray> {
        val (filename, bytes) = reconciliationExports.export(batchId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }

    @PostMapping("/batches/{batchId}/confirm")
    fun confirm(@PathVariable batchId: String, @Valid @RequestBody request: MappingRequest,auth:Authentication?=null):ResponseEntity<ApiResponse<ImportBatchView>> {
        if(auth!=null)requireSdkAdmin(service.channelOf(batchId),auth)
        val actor=(auth?.principal as? WebPrincipal)?.userId
        return ResponseEntity.accepted().body(ApiResponse.success(service.confirm(batchId, request.mapping, actor)))
    }

    @PostMapping("/batches/{batchId}/retry")
    fun retry(@PathVariable batchId: String, @RequestBody(required = false) request: MappingRequest?,auth:Authentication?=null):ResponseEntity<ApiResponse<ImportBatchView>> {
        if(auth!=null)requireSdkAdmin(service.channelOf(batchId),auth);return ResponseEntity.accepted().body(ApiResponse.success(service.retry(batchId, request?.mapping)))
    }
    @PostMapping("/batches/{batchId}/retry-technical")
    fun retryTechnical(@PathVariable batchId:String,auth:Authentication?=null):ResponseEntity<ApiResponse<ImportBatchView>>{
        if(auth!=null)requireSdkAdmin(service.channelOf(batchId),auth)
        return ResponseEntity.accepted().body(ApiResponse.success(service.retryTechnicalFailures(batchId)))
    }

    @PostMapping("/batches/{batchId}/rollback")
    fun rollbackPost(@PathVariable batchId: String,auth:Authentication?=null):ApiResponse<ImportBatchView> { if(auth!=null)requireSdkAdmin(service.channelOf(batchId),auth);return ApiResponse.success(service.rollback(batchId)) }

    @DeleteMapping("/batches/{batchId}/rollback")
    fun rollbackDelete(@PathVariable batchId: String,auth:Authentication?=null):ApiResponse<ImportBatchView> { if(auth!=null)requireSdkAdmin(service.channelOf(batchId),auth);return ApiResponse.success(service.rollback(batchId)) }

    private fun requireSdkAdmin(channel:String,auth:Authentication?){
        if(channel.trim().uppercase()!="SDK")return
        val principal=auth?.principal as? WebPrincipal?:throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.AUTH_UNAUTHORIZED,"AUTHENTICATION_REQUIRED")
        if(RoleCode.ADMIN !in principal.roles)throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.AUTH_FORBIDDEN,"SDK_SIMULATED_IMPORT_ADMIN_REQUIRED")
    }
}

data class MappingRequest(@field:Size(min=2,max=100) val mapping: Map<String, String>)
data class CompleteChunkUploadRequest(@field:NotBlank @field:Size(max=255) val filename:String,@field:Size(min=64,max=64) val fullSha256:String,val sheetName:String?=null,val dataDateFrom:LocalDate?=null,val dataDateTo:LocalDate?=null)
data class JiraSyncRequest(val from:LocalDateTime?=null,val to:LocalDateTime?=null)
data class JiraFormalReviewRequest(@field:Size(min=1,max=100) val issueKeys:List<String>)
data class JiraLogAttachmentRequest(@field:Size(min=1,max=20) val issueKeys:List<String>)
data class ImportBatchCleanupRequest(@field:Size(min=10,max=500) val reason:String)
