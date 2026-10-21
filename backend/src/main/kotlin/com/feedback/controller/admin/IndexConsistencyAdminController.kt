package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.sync.IndexConsistencyAdminService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class IndexReplayRequest(val reason:String)

@RestController
@RequestMapping("/api/v1/admin/index-consistency")
class IndexConsistencyAdminController(private val service:IndexConsistencyAdminService){
    @GetMapping fun status()=ApiResponse.success(service.status())
    @PostMapping("/outbox/{id}/replay") fun replay(@PathVariable id:Long,@RequestBody input:IndexReplayRequest,auth:Authentication):ApiResponse<Map<String,Boolean>>{
        service.replay(id,input.reason,(auth.principal as WebPrincipal).userId);return ApiResponse.success(mapOf("replayed" to true))
    }
    @PostMapping("/statistics/reconcile") fun reconcile(auth:Authentication)=
        ApiResponse.success(service.reconcileStatistics((auth.principal as WebPrincipal).userId))
}
