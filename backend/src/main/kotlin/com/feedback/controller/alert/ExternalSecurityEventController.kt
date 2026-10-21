package com.feedback.controller.alert

import com.feedback.common.ApiResponse
import com.feedback.service.alert.ExternalSecurityEventService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/provider/security-events")
class ExternalSecurityEventController(private val service:ExternalSecurityEventService) {
    @PostMapping fun accept(@RequestBody body:String,@RequestHeader("X-Security-Timestamp") timestamp:String,
        @RequestHeader("X-Security-Signature") signature:String)=ApiResponse.success(service.accept(body,timestamp,signature))
}
