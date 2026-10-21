package com.feedback.controller.alert

import com.feedback.common.ApiResponse
import com.feedback.service.alert.NotificationProviderReceiptService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/provider/notification-receipts")
class NotificationProviderReceiptController(private val service:NotificationProviderReceiptService){
    @PostMapping fun accept(@RequestBody body:String,@RequestHeader("X-Receipt-Timestamp") timestamp:String,
        @RequestHeader("X-Receipt-Signature") signature:String)=ApiResponse.success(service.accept(body,timestamp,signature))
}
