package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.cluster.HistoricalClusterShadowService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 管理员限定的只读影子评测入口；路径权限由统一安全配置控制。 */
@RestController
@RequestMapping("/api/v1/admin/cluster-shadow-evaluation")
class ClusterShadowEvaluationController(
    private val service: HistoricalClusterShadowService
) {
    @PostMapping
    @Operation(
        summary = "执行有界的真实历史只读影子评测",
        description = "最多读取 100 条，不写入 Cluster、MAIN 或 ACTIVE generation；功能开关默认关闭。"
    )
    fun evaluate(@Valid @RequestBody request: ClusterShadowEvaluationRequest) = ApiResponse.success(
        service.evaluate(request.afterSourceId, request.limit).response()
    )
}
