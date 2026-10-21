package com.feedback.controller.report

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.report.*
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reports")
class ReportController(
    private val reports: ReportService,
    private val generation: ReportGenerationOrchestrator,
    private val pdf: ReportPdfService,
    private val clusters: ClusterReportService
) {
    @GetMapping fun list(@RequestParam(required=false) type:String?, @RequestParam(defaultValue="1") page:Int,
        @RequestParam(defaultValue="20") size:Int) = ApiResponse.success(reports.list(type,page,size))
    @GetMapping("/{id}") fun detail(@PathVariable id:Long) = ApiResponse.success(reports.detail(id))
    @DeleteMapping("/{id}") fun delete(@PathVariable id:Long,auth:Authentication) =
        ApiResponse.success(reports.delete(id,principal(auth).userId))
    @PostMapping("/generate") fun generate(@RequestBody request:GenerateReportRequest, auth:Authentication) =
        ApiResponse.success(generation.generate(request, principal(auth).userId))
    @GetMapping("/tasks/{id}") fun task(@PathVariable id:Long,auth:Authentication)=ApiResponse.success(generation.task(id,principal(auth).userId))
    @GetMapping("/tasks") fun tasks(auth:Authentication)=ApiResponse.success(generation.taskList(principal(auth).userId))
    @PostMapping("/tasks/{id}/cancel") fun cancelTask(@PathVariable id:Long,auth:Authentication)=ApiResponse.success(generation.cancel(id,principal(auth).userId))
    @PostMapping("/tasks/{id}/retry") fun retryTask(@PathVariable id:Long,auth:Authentication)=ApiResponse.success(generation.retry(id,principal(auth).userId))
    @GetMapping("/{id}/pdf") fun export(@PathVariable id:Long):ResponseEntity<ByteArray> {
        val (name, bytes)=pdf.export(id)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).body(bytes)
    }

    @GetMapping("/cluster/{id}/rca") fun clusterRca(@PathVariable id:Long)=ApiResponse.success(clusters.rca(id))
    @GetMapping("/cluster/{id}/knowledge") fun knowledge(@PathVariable id:Long)=ApiResponse.success(clusters.knowledge(id))
    @GetMapping("/cluster/{id}/incidents") fun incidents(@PathVariable id:Long)=ApiResponse.success(clusters.incidents(id))
    @GetMapping("/feedback/{id}/rca-knowledge") fun feedbackRcaKnowledge(@PathVariable id:Long)=ApiResponse.success(clusters.feedbackRcaKnowledge(id))

    private fun principal(auth:Authentication)=auth.principal as WebPrincipal
}
