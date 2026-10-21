package com.feedback.controller.alert

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.alert.InAppNotificationService
import com.feedback.service.alert.InAppMarkAllReadResponse
import com.feedback.service.alert.InAppReadResponse
import com.feedback.service.alert.InAppUnreadCountResponse
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/in-app-notifications")
class InAppNotificationController(private val service:InAppNotificationService) {
    @GetMapping fun list(@RequestParam(defaultValue="1")page:Int,@RequestParam(defaultValue="20")size:Int,@RequestParam(defaultValue="false")unreadOnly:Boolean,auth:Authentication)=ApiResponse.success(service.list(user(auth),page,size,unreadOnly))
    @GetMapping("/unread-count") fun unreadCount(auth:Authentication)=ApiResponse.success(InAppUnreadCountResponse(service.unreadCount(user(auth))))
    @PostMapping("/{id}/read") fun markRead(@PathVariable id:Long,auth:Authentication)=ApiResponse.success(InAppReadResponse(service.markRead(user(auth),id)))
    @PostMapping("/read-all") fun markAllRead(auth:Authentication)=ApiResponse.success(InAppMarkAllReadResponse(service.markAllRead(user(auth))))
    private fun user(auth:Authentication)=(auth.principal as WebPrincipal).userId
}
