package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.query.analysis.ClusterShadowPreviewDetailResponse
import com.feedback.service.query.analysis.ClusterShadowPreviewPageResponse
import com.feedback.service.query.analysis.ClusterShadowPreviewQuery
import com.feedback.service.query.analysis.ClusterShadowPreviewQueryUseCase
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/admin/cluster-shadow-preview")
class ClusterShadowPreviewController(private val preview: ClusterShadowPreviewQueryUseCase) {
    @Operation(summary = "只读预览白名单 VERIFIED shadow generation")
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam(defaultValue = "false") largeOnly: Boolean,
        @RequestParam(defaultValue = "false") provisionalOnly: Boolean,
        @RequestParam(defaultValue = "false") crossChannelOnly: Boolean,
        @RequestParam(required = false) application: String?,
        @RequestParam(required = false) analysisSection: String?
    ): ApiResponse<ClusterShadowPreviewPageResponse> = ApiResponse.success(
        preview.list(ClusterShadowPreviewQuery(page, size, largeOnly, provisionalOnly, crossChannelOnly, application, analysisSection))
    )

    @Operation(summary = "读取 shadow 候选簇匿名成员投影（无正文及来源标识）")
    @GetMapping("/clusters/{previewId}")
    fun detail(
        @PathVariable @Pattern(regexp = "SC-[0-9]{4}") previewId: String,
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int
    ): ApiResponse<ClusterShadowPreviewDetailResponse> = ApiResponse.success(
        preview.detail(previewId, page, size)
    )
}
