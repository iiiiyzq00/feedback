package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.service.query.analysis.ClusterDetailResponse
import com.feedback.service.query.analysis.ClusterFeedbackPageResponse
import com.feedback.service.query.analysis.ClusterReadUseCase
import com.feedback.service.query.analysis.ProblemAnalysisResponse
import com.feedback.service.query.analysis.ProblemAnalysisQueryUseCase
import com.feedback.service.query.SearchSuggestionQueryUseCase
import com.feedback.service.query.SearchSuggestionResponse
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 反馈问题深度分析 HTTP 适配器；两个明细页共享同一具名请求且保持独立分页。 */
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class ProblemAnalysisController(private val analysis: ProblemAnalysisQueryUseCase) {
    @Operation(
        summary = "查询反馈问题深度分析",
        description = "需要反馈工作区权限。时间范围必须同时为空或同时提供；两个问题簇集合独立分页。读取固定一个具备问题分析能力的完整 ACTIVE generation，读开关关闭或投影事实不完整时失败关闭，不回退旧查询。"
    )
    @GetMapping("/problem-analysis")
    fun problemAnalysis(
        @Valid @ModelAttribute request: ProblemAnalysisRequest
    ): ApiResponse<ProblemAnalysisResponse> = ApiResponse.success(
        analysis.analyze(request.toQuery())
    )
}

/** 反馈问题簇详情 HTTP 适配器；RCA、知识库和 Incident 路由继续留在非反馈报告控制器。 */
@Validated
@RestController
@RequestMapping("/api/v1/reports")
class ClusterReadController(private val analysis: ClusterReadUseCase) {
    @Operation(
        summary = "查询反馈问题簇详情",
        description = "需要反馈工作区权限或既有 AI 报告兼容权限。按共享渠道、模块和地域范围读取；时间范围必须同时为空或同时提供。响应只组装固定 ACTIVE generation 的投影事实，必要事实缺失时不返回推测结果。"
    )
    @GetMapping("/cluster/{id}")
    fun cluster(
        @PathVariable @Positive id: Long,
        @Valid @ModelAttribute request: ClusterTimeRangeRequest
    ): ApiResponse<ClusterDetailResponse> = ApiResponse.success(
        analysis.detail(request.toQuery(id))
    )

    @Operation(
        summary = "分页查询反馈问题簇内反馈",
        description = "需要反馈工作区权限或既有 AI 报告兼容权限。先分页读取簇成员，再批量读取同一 ACTIVE generation 的安全正文投影；任一页内投影缺失时整体失败，不返回部分页面。"
    )
    @GetMapping("/cluster/{id}/feedback")
    fun feedback(
        @PathVariable @Positive id: Long,
        @Valid @ModelAttribute request: ClusterFeedbackRequest
    ): ApiResponse<ClusterFeedbackPageResponse> = ApiResponse.success(
        analysis.feedback(request.toFeedbackQuery(id))
    )
}

/** 反馈搜索建议 HTTP 适配器；相似搜索保持非反馈兼容边界。 */
@Validated
@RestController
@RequestMapping("/api/v1/search")
class SearchSuggestionController(private val suggestions: SearchSuggestionQueryUseCase) {
    @Operation(
        summary = "查询反馈搜索建议",
        description = "需要反馈工作区权限。复用反馈安全列表读取，最多从首批 100 条候选中按渠道各取两条并受 limit 限制；渠道计数仅描述该批候选，total 表示完整列表匹配总数。读取不可用时失败关闭。"
    )
    @GetMapping("/suggest")
    fun suggest(
        @Valid @ModelAttribute request: SearchSuggestionRequest
    ): ApiResponse<SearchSuggestionResponse> = ApiResponse.success(
        suggestions.suggestions(request.q, request.limit)
    )
}
