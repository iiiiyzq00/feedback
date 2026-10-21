package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.applicationidentity.ApplicationIdentityAdminService
import com.feedback.service.applicationidentity.ApplicationIdentityShadowService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/application-identities")
/** 管理员应用身份 HTTP 入口；仅依赖 Service 接口并保持既有 v1 JSON code。 */
class ApplicationIdentityAdminController(
    private val adminService: ApplicationIdentityAdminService,
    private val shadowService: ApplicationIdentityShadowService
) {
    /** 查询不含敏感正文的应用身份解析诊断。 */
    @GetMapping("/diagnostics")
    fun diagnostics() = ApiResponse.success(adminService.diagnostics().response())

    /** 按 UUID 游标查询有界 identity 页面。 */
    @GetMapping
    fun list(
        @RequestParam(required = false) afterApplicationId: String?,
        @RequestParam(defaultValue = "50") limit: Int
    ) = ApiResponse.success(adminService.list(afterApplicationId, limit).response())

    /** 查询指定 identity 的有界 alias 历史；规范化 alias 仅管理员可见。 */
    @GetMapping("/{applicationId}/aliases")
    fun aliases(
        @PathVariable applicationId: String
    ) = ApiResponse.success(adminService.aliases(applicationId).map { it.response() })

    /** 显式触发最多 100 条的影子批次，不修改活动归簇。 */
    @PostMapping("/shadow-batches")
    fun shadowBatch(@Valid @RequestBody request: ApplicationIdentityShadowBatchRequest) = ApiResponse.success(
        shadowService.process(request.afterSourceId, request.limit, request.resolutionRuleVersion).response()
    )

    /** 以双版本 CAS 与 requestId 幂等执行人工 alias 重绑。 */
    @PostMapping("/{applicationId}/aliases/rebind")
    fun rebind(
        @PathVariable applicationId: String,
        @Valid @RequestBody request: ApplicationAliasRebindRequest
    ) = ApiResponse.success(adminService.rebind(request.command(applicationId)).response())

    /** 将授权审核结论写为新 identity 或既有 identity alias。 */
    @PostMapping("/reviewed-bindings")
    fun createReviewedBinding(
        @Valid @RequestBody request: ApplicationReviewedBindingRequest
    ) = ApiResponse.success(adminService.createReviewedBinding(request.command()).response())

}
