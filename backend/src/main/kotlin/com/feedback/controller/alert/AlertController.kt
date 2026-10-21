package com.feedback.controller.alert

import com.feedback.common.ApiResponse
import com.feedback.service.alert.AlertQueryService
import com.feedback.service.alert.AlertIncidentLifecycleService
import com.feedback.service.alert.EntityClosureService
import com.feedback.service.knowledge.KnowledgeClosureService
import com.feedback.service.knowledge.KnowledgeUsageInput
import org.springframework.web.bind.annotation.*

data class AlertActionRequest(val expectedVersion:Long)
data class ResolveAlertRequest(val resolution: String = "",val expectedVersion:Long)
data class TransferAlertRequest(val assigneeId:Long,val reason:String="",val expectedIncidentVersion:Long)
data class SilenceAlertRequest(val minutes:Int=30,val reason:String="",val expectedVersion:Long)
data class LifecycleTransitionRequest(val targetStatus:String="",val expectedVersion:Long=0,val reason:String="",val evidence:Map<String,Any?> = emptyMap())

@RestController
@RequestMapping("/api/v1/alerts")
class AlertController(private val service: AlertQueryService,private val lifecycle:AlertIncidentLifecycleService,private val knowledge:KnowledgeClosureService,private val entityClosure:EntityClosureService) {
    @GetMapping fun list(@RequestParam(required=false) level:String?, @RequestParam(required=false) status:String?,@RequestParam(required=false) alertId:Long?,@RequestParam(required=false) clusterId:Long?,
        @RequestParam(defaultValue="1") page:Int, @RequestParam(defaultValue="20") size:Int) = ApiResponse.success(service.list(level,status,page,size,alertId,clusterId))
    @GetMapping("/statistics") fun statistics() = ApiResponse.success(service.statistics())
    @GetMapping("/p3-digest") fun p3Digest()=ApiResponse.success(service.p3Digest())
    @GetMapping("/assignees") fun assignees()=ApiResponse.success(service.assignees())
    @GetMapping("/{id}") fun detail(@PathVariable id:Long) = ApiResponse.success(service.detail(id))
    @GetMapping("/{id}/evidence") fun evidence(@PathVariable id:Long)=ApiResponse.success(service.evidence(id))
    @PostMapping("/{id}/ack") fun ack(@PathVariable id:Long,@RequestBody request:AlertActionRequest,auth:org.springframework.security.core.Authentication): ApiResponse<Map<String, Boolean>> { service.acknowledge(id,request.expectedVersion,(auth.principal as com.feedback.security.WebPrincipal).userId); return ApiResponse.success(mapOf("acknowledged" to true)) }
    @PostMapping("/{id}/resolve") fun resolve(@PathVariable id:Long,@RequestBody request:ResolveAlertRequest,auth:org.springframework.security.core.Authentication): ApiResponse<Map<String, Boolean>> { service.resolve(id,request.resolution,request.expectedVersion,(auth.principal as com.feedback.security.WebPrincipal).userId); return ApiResponse.success(mapOf("resolved" to true)) }
    @PostMapping("/{id}/transfer") fun transfer(@PathVariable id:Long,@RequestBody request:TransferAlertRequest,auth:org.springframework.security.core.Authentication):ApiResponse<Map<String,Boolean>>{service.transfer(id,request.assigneeId,request.reason,request.expectedIncidentVersion,(auth.principal as com.feedback.security.WebPrincipal).userId);return ApiResponse.success(mapOf("transferred" to true))}
    @PostMapping("/{id}/silence") fun silence(@PathVariable id:Long,@RequestBody request:SilenceAlertRequest,auth:org.springframework.security.core.Authentication):ApiResponse<Map<String,Boolean>>{service.silence(id,request.minutes,request.reason,request.expectedVersion,(auth.principal as com.feedback.security.WebPrincipal).userId);return ApiResponse.success(mapOf("silenced" to true))}
    @PostMapping("/{id}/transition") fun transitionAlert(@PathVariable id:Long,@RequestBody request:LifecycleTransitionRequest,auth:org.springframework.security.core.Authentication):ApiResponse<Map<String,Boolean>>{lifecycle.transitionAlert(id,request.targetStatus,request.expectedVersion,request.reason,(auth.principal as com.feedback.security.WebPrincipal).userId,request.evidence);return ApiResponse.success(mapOf("transitioned" to true))}
    @GetMapping("/{id}/lifecycle") fun alertLifecycle(@PathVariable id:Long)=ApiResponse.success(lifecycle.timeline("ALERT",id))
    @GetMapping("/incidents") fun incidents(@RequestParam(defaultValue="1") page:Int,@RequestParam(defaultValue="20") size:Int) = ApiResponse.success(service.incidents(page,size))
    @GetMapping("/incidents/{id}") fun incident(@PathVariable id:Long) = ApiResponse.success(service.incident(id))
    @PostMapping("/incidents/{id}/transition") fun transitionIncident(@PathVariable id:Long,@RequestBody request:LifecycleTransitionRequest,auth:org.springframework.security.core.Authentication):ApiResponse<Map<String,Boolean>>{lifecycle.transitionIncident(id,request.targetStatus,request.expectedVersion,request.reason,(auth.principal as com.feedback.security.WebPrincipal).userId,request.evidence);return ApiResponse.success(mapOf("transitioned" to true))}
    @GetMapping("/incidents/{id}/lifecycle") fun incidentLifecycle(@PathVariable id:Long)=ApiResponse.success(lifecycle.timeline("INCIDENT",id))
    @GetMapping("/incidents/{id}/entity-closure") fun entityClosure(@PathVariable id:Long)=ApiResponse.success(entityClosure.snapshot(id))
    @PostMapping("/incidents/{incidentId}/knowledge/{knowledgeId}/usage") fun knowledgeUsage(@PathVariable incidentId:Long,@PathVariable knowledgeId:Long,@RequestBody input:KnowledgeUsageInput,auth:org.springframework.security.core.Authentication)=ApiResponse.success(knowledge.usage(incidentId,knowledgeId,input,(auth.principal as com.feedback.security.WebPrincipal).userId))
}
