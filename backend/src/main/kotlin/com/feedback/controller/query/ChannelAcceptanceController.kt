package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.service.query.ChannelAcceptanceInput
import com.feedback.service.query.ChannelAcceptanceService
import com.feedback.service.query.ChannelViewConfigInput
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/channel-governance")
class ChannelAcceptanceController(private val service:ChannelAcceptanceService) {
    @GetMapping("/overview") fun overview()=ApiResponse.success(service.overview())
    @PostMapping("/{channel}/acceptance") fun accept(@PathVariable channel:String,@RequestBody input:ChannelAcceptanceInput)=ApiResponse.success(service.accept(channel,input))
    @GetMapping("/{channel}/view-config") fun config(@PathVariable channel:String)=ApiResponse.success(service.config(channel))
    @PostMapping("/{channel}/view-config/drafts") fun draft(@PathVariable channel:String,@RequestBody input:ChannelViewConfigInput)=ApiResponse.success(service.draft(channel,input))
    @PostMapping("/{channel}/view-config/{version}/publish") fun publish(@PathVariable channel:String,@PathVariable version:Long)=ApiResponse.success(service.publish(channel,version))
    @PostMapping("/{channel}/view-config/{version}/rollback") fun rollback(@PathVariable channel:String,@PathVariable version:Long,@RequestBody input:Map<String,String>)=ApiResponse.success(service.rollback(channel,version,input["reason"]?:""))
    @GetMapping("/{channel}/view-config/history") fun history(@PathVariable channel:String)=ApiResponse.success(service.history(channel))
}
