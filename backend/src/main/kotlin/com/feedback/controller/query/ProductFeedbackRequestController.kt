package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.service.query.ProductFeedbackInput
import com.feedback.service.query.ProductFeedbackRequestService
import com.feedback.service.query.ProductFeedbackStatusInput
import com.feedback.service.query.ProductFeedbackCommentInput
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/product-feedback")
class ProductFeedbackRequestController(private val service:ProductFeedbackRequestService) {
    @PostMapping fun create(@RequestBody input:ProductFeedbackInput)=ApiResponse.success(service.create(input))
    @GetMapping fun list(@RequestParam(required=false)status:String?,@RequestParam(required=false)type:String?,@RequestParam(defaultValue="false")mine:Boolean,@RequestParam(defaultValue="1")page:Int,@RequestParam(defaultValue="20")size:Int)=ApiResponse.success(service.list(status,type,mine,page,size))
    @PutMapping("/{id}") fun update(@PathVariable id:Long,@RequestBody input:ProductFeedbackStatusInput)=ApiResponse.success(service.update(id,input))
    @GetMapping("/{id}") fun detail(@PathVariable id:Long)=ApiResponse.success(service.detail(id))
    @PostMapping("/{id}/comments") fun comment(@PathVariable id:Long,@RequestBody input:ProductFeedbackCommentInput)=ApiResponse.success(service.comment(id,input))
    @GetMapping("/export") fun export()=ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=product-feedback.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body("\uFEFF"+service.export())
}
