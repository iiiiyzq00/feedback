package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.service.query.SimilarFeedbackService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/search")
class SearchController(private val similar: SimilarFeedbackService) {
    @GetMapping("/similar")
    fun similar(@RequestParam q: String, @RequestParam(defaultValue = "10") topK: Int) = ApiResponse.success(similar.search(q, topK))
}
