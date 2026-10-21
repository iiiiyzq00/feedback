package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.service.station.StationFeedbackOperationsService
import com.feedback.service.station.StationOnlineRetryService
import com.feedback.model.station.StationRetryRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** 反馈接入运维入口；只依赖 Station 运维 Service 契约并执行管理员授权。 */
@RestController
@RequestMapping("/api/v1/ingestion-operations")
class IngestionOperationsController(
    private val station: StationFeedbackOperationsService,
    private val stationRetry: StationOnlineRetryService
) {
    /** 分页查询 Station 运维任务；状态、页码和页大小由应用服务执行领域校验。 */
    @GetMapping("/station")
    fun stationTasks(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ) = ApiResponse.success(station.tasks(status, page, size))

    /** 提交有界 Station 人工重试命令；授权、回执去重和锁定由应用服务负责。 */
    @PostMapping("/station/retry")
    fun retryStation(@Valid @RequestBody input: StationRetryRequest) =
        ApiResponse.success(stationRetry.retry(input))
}
