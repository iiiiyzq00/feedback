package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.alert.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/alert-delivery")
class DeadLetterController(private val service:DeadLetterService,private val operations:NotificationOperationsService){
    @GetMapping("/matrix") fun matrix()=ApiResponse.success(operations.matrix())
    @GetMapping("/overview") fun overview(@RequestParam(defaultValue="24")hours:Int)=ApiResponse.success(operations.overview(hours))
    @GetMapping("/notifications") fun notifications(@RequestParam(defaultValue="1")page:Int,@RequestParam(defaultValue="20")size:Int,@RequestParam(required=false)channel:String?,@RequestParam(required=false)status:String?,@RequestParam(required=false)alertInstanceId:Long?)=ApiResponse.success(operations.list(page,size,channel,status,alertInstanceId))
    @GetMapping("/dead-letters") fun list(@RequestParam(defaultValue="1")page:Int,@RequestParam(defaultValue="20")size:Int,@RequestParam(required=false)channel:String?)=ApiResponse.success(service.list(page,size,channel))
    @GetMapping("/notifications/{id}") fun detail(@PathVariable id:Long)=ApiResponse.success(service.detail(id))
    @PostMapping("/dead-letters/{id}/replay") fun replay(@PathVariable id:Long,@RequestBody input:DeadLetterAction)=ApiResponse.success(service.replay(id,input))
    @PostMapping("/dead-letters/{id}/ignore") fun ignore(@PathVariable id:Long,@RequestBody input:DeadLetterAction)=ApiResponse.success(service.ignore(id,input))
    @PostMapping("/notifications/{id}/evidence") fun evidence(@PathVariable id:Long,@RequestBody input:DeliveryEvidenceInput)=ApiResponse.success(service.addEvidence(id,input))
}
