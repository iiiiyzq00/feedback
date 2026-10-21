package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.ai.AiReviewRequest
import com.feedback.service.ai.AiBatchReviewRequest
import com.feedback.service.ai.AiReviewService
import com.feedback.service.ai.AiClassificationTaxonomy
import com.feedback.service.ai.SeverityPolicyService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/ai-reviews")
class AiReviewController(private val service: AiReviewService, private val severityPolicy: SeverityPolicyService? = null) {
    @GetMapping("/taxonomy") fun taxonomy() = ApiResponse.success(AiClassificationTaxonomy.contract(severityPolicy?.levels() ?: AiClassificationTaxonomy.severities))
    @GetMapping fun pending(@RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required=false) reason:String?) = ApiResponse.success(service.pending(page, size,reason))

    @PostMapping("/{sourceId}") fun review(@PathVariable sourceId: Long, @RequestBody request: AiReviewRequest, auth: Authentication): ApiResponse<Map<String, Boolean>> {
        service.review(sourceId, request, (auth.principal as WebPrincipal).userId)
        return ApiResponse.success(mapOf("reviewed" to true))
    }

    @PostMapping("/batch") fun reviewBatch(@RequestBody request: AiBatchReviewRequest, auth: Authentication) =
        ApiResponse.success(mapOf("reviewed" to service.reviewBatch(request, (auth.principal as WebPrincipal).userId)))
}
