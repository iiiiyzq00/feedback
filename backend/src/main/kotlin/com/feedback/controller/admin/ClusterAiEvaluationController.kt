package com.feedback.controller.admin
import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.cluster.*
import org.springframework.security.core.Authentication
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
@RestController @RequestMapping("/api/v1/admin/cluster-evaluation")
class ClusterAiEvaluationController(private val service:ClusterAiEvaluationService){
 @PostMapping fun evaluate(@RequestBody input:ClusterEvaluationRequest,auth:Authentication)=ApiResponse.success(service.evaluate(input,(auth.principal as WebPrincipal).userId))
 @GetMapping("/latest") fun latest()=ApiResponse.success(service.latest())
 @GetMapping("/latest/export") fun exportLatest():ResponseEntity<ByteArray> = ResponseEntity.ok()
  .contentType(MediaType.APPLICATION_JSON)
  .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=cluster-evaluation-latest.json")
  .body(service.exportLatest())
}
