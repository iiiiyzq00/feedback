package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.service.masking.MaskingRuleRegistry
import com.feedback.service.masking.MaskingRuleUpdate
import com.feedback.service.masking.*
import org.springframework.web.bind.annotation.*

data class MaskingPublishInput(val reason:String="")
data class NerRetireInput(val expectedVersion:Long=0,val reason:String="")
data class MaskingTestInput(val text:String="")

@RestController
@RequestMapping("/api/v1/admin/masking-rules")
class MaskingRuleAdminController(private val rules:MaskingRuleRegistry,private val allowlist:NerAllowlistRegistry,private val versions:MaskingVersionService,private val evidence:MaskingEvaluationEvidenceService,private val pipeline:MaskingPipeline){
    @GetMapping fun list()=ApiResponse.success(rules.list())
    @PostMapping("/test") fun test(@RequestBody input:MaskingTestInput):ApiResponse<Map<String,Any>> { if(input.text.isBlank()||input.text.length>5000) throw ClientException(ErrorCode.BAD_REQUEST,"MASKING_TEST_TEXT_INVALID");val result=pipeline.mask(input.text,"admin-online-test",false);return ApiResponse.success(mapOf("maskedText" to result.maskedText,"maskingStatus" to result.maskingStatus,"findingTypes" to result.findingTypes,"ruleVersion" to rules.activeVersion())) }
    @PutMapping("/{id}") fun update(@PathVariable id:Int,@RequestBody input:MaskingRuleUpdate,auth:org.springframework.security.core.Authentication)=ApiResponse.success(rules.update(id,input,actor(auth)))
    @GetMapping("/ner-allowlist") fun allowlist()=ApiResponse.success(allowlist.list())
    @PostMapping("/ner-allowlist") fun addAllowlist(@RequestBody input:NerAllowlistInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(allowlist.add(input,actor(auth)))
    @PostMapping("/ner-allowlist/{id}/retire") fun retireAllowlist(@PathVariable id:Long,@RequestBody input:NerRetireInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(allowlist.retire(id,input.expectedVersion,input.reason,actor(auth)))
    @GetMapping("/versions") fun versions()=ApiResponse.success(versions.versions())
    @PostMapping("/versions") fun createVersion(@RequestBody input:MaskingCandidateInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(versions.candidate(input,actor(auth)))
    @PostMapping("/versions/{version}/evaluation") fun evaluate(@PathVariable version:String,@RequestBody input:MaskingEvaluationInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(versions.evaluate(version,input,actor(auth)))
    @GetMapping("/datasets") fun datasets()=ApiResponse.success(evidence.datasets())
    @PostMapping("/datasets") fun registerDataset(@RequestBody input:MaskingDatasetInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(evidence.registerDataset(input,actor(auth)))
    @PostMapping("/datasets/{version}/review") fun reviewDataset(@PathVariable version:String,auth:org.springframework.security.core.Authentication)=ApiResponse.success(evidence.reviewDataset(version,actor(auth)))
    @PostMapping("/datasets/{version}/approve") fun approveDataset(@PathVariable version:String,auth:org.springframework.security.core.Authentication)=ApiResponse.success(evidence.approveDataset(version,actor(auth)))
    @GetMapping("/versions/{version}/runs") fun runs(@PathVariable version:String)=ApiResponse.success(evidence.runs(version))
    @PostMapping("/versions/{version}/runs") fun execute(@PathVariable version:String,@RequestBody input:MaskingEvaluationRunInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(evidence.execute(version,input,actor(auth)))
    @PostMapping("/runs/{id}/approve") fun approveRun(@PathVariable id:Long,@RequestBody input:MaskingRunApprovalInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(evidence.approveRun(id,input,actor(auth)))
    @PostMapping("/versions/{version}/publish") fun publish(@PathVariable version:String,@RequestBody input:MaskingPublishInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(versions.publish(version,input.reason,actor(auth)))
    private fun actor(auth:org.springframework.security.core.Authentication)=(auth.principal as com.feedback.security.WebPrincipal).userId
}
