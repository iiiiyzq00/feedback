package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.config.DynamicConfigBatchChangeRequest
import com.feedback.config.DynamicConfigChangeCommand
import com.feedback.config.DynamicConfigChangeItemCommand
import com.feedback.config.DynamicConfigGovernanceService
import com.feedback.config.DynamicConfigHistoryQuery
import com.feedback.config.DynamicConfigRollbackCommand
import com.feedback.config.DynamicConfigRollbackRequest
import com.feedback.config.DynamicConfigSingleChangeRequest
import jakarta.validation.Valid
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 动态配置 HTTP 适配器；权限由统一安全规则控制，操作者由应用服务的公共主体组件解析。 */
@Validated
@RestController
@RequestMapping("/api/v1/admin/configs")
class DynamicConfigAdminController(private val service: DynamicConfigGovernanceService) {
    @GetMapping
    fun current() = ApiResponse.success(service.current())

    @PutMapping("/{key}")
    fun change(@PathVariable key: String, @Valid @RequestBody request: DynamicConfigSingleChangeRequest) =
        ApiResponse.success(
            service.change(
                DynamicConfigChangeCommand(
                    listOf(DynamicConfigChangeItemCommand(key, request.value, request.expectedVersion)),
                    request.reason
                )
            )
        )

    @PostMapping("/change-set")
    fun changeSet(@Valid @RequestBody request: DynamicConfigBatchChangeRequest) = ApiResponse.success(
        service.change(
            DynamicConfigChangeCommand(
                request.changes.map { DynamicConfigChangeItemCommand(it.key, it.value, it.expectedVersion) },
                request.reason
            )
        )
    )

    @GetMapping("/{key}/history")
    fun history(@PathVariable key: String, @Valid @ModelAttribute query: DynamicConfigHistoryQuery) =
        ApiResponse.success(service.history(key, query.page, query.size))

    @PostMapping("/{key}/rollback")
    fun rollback(@PathVariable key: String, @Valid @RequestBody request: DynamicConfigRollbackRequest) =
        ApiResponse.success(
            service.rollback(
                DynamicConfigRollbackCommand(
                    key, request.targetVersion, request.expectedVersion, request.reason
                )
            )
        )
}
