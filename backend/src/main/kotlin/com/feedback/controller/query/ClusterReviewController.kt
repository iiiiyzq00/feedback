package com.feedback.controller.query

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.query.ClusterReviewCommand
import com.feedback.service.query.ClusterReviewService
import com.feedback.service.query.ClusterBatchReviewItem
import com.feedback.service.cluster.ClusterLifecycleService
import com.feedback.service.cluster.ClusterReviewGovernance
import com.feedback.service.cluster.SplitPartition
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import io.swagger.v3.oas.annotations.media.Schema

data class ConfirmClusterRequest(
    @field:Positive @field:Schema(description = "确认关联的目标问题簇 ID；操作者必须拥有目标簇全部成员的数据范围", example = "301") val clusterId: Long,
    @field:PositiveOrZero @field:Schema(description = "候选关联的期望审核版本，精确 CAS", example = "2") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突", example = "1d13c831-5fa6-4c2e-8df1-593366412822") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "人工确认原因，3 至 500 字；仅进入业务审计，不包含敏感正文", example = "确认与目标簇属于同一问题") val reason: String
)
data class CreateClusterRequest(
    @field:Positive @field:Schema(description = "待归簇反馈 source ID；须通过渠道、模块和区域范围校验", example = "9001") val sourceId: Long,
    @field:Size(min=2,max=200) @field:Schema(description = "新问题簇名称，2 至 200 字，必须覆盖可操作问题画像", example = "学习机视频播放无响应") val name: String,
    @field:PositiveOrZero @field:Schema(description = "候选关联的期望审核版本，精确 CAS", example = "0") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "创建原因，3 至 500 字；不得填写敏感正文") val reason: String
)
data class ClusterReviewActionRequest(
    @field:PositiveOrZero @field:Schema(description = "期望审核版本，精确 CAS", example = "1") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "操作原因，3 至 500 字；不得填写敏感正文") val reason: String
)
data class ClusterStatusRequest(
    @field:Pattern(regexp = "MITIGATING|RESOLVED") @field:Schema(description = "目标状态；仍受服务端状态机限制", allowableValues = ["MITIGATING","RESOLVED"]) val status: String,
    @field:PositiveOrZero @field:Schema(description = "目标簇期望 lifecycle_version，精确 CAS") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "状态变更原因，3 至 500 字") val reason: String
)
data class ClusterMergeRequest(
    @field:Size(min=1,max=200) @field:Schema(description = "被合并簇 ID；去重后必须与目标簇不同，实际迁移 source 总数最多 200") val sourceClusterIds: List<Long>,
    @field:PositiveOrZero @field:Schema(description = "目标簇期望 lifecycle_version，精确 CAS") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "合并原因，3 至 500 字") val reason: String
)
data class ClusterSplitPartitionRequest(
    @field:Size(min=1,max=200) @field:Schema(description = "拆分后问题簇名称，必须覆盖分区成员画像") val name: String,
    @field:Size(min=1,max=200) @field:Schema(description = "该分区 source ID；全部分区必须精确覆盖原簇且总数最多 200") val sourceIds: List<Long>
)
data class ClusterSplitRequest(
    @field:Size(min=2,max=200) @field:Valid @field:Schema(description = "至少两个拆分分区；总 source 数最多 200") val partitions: List<ClusterSplitPartitionRequest>,
    @field:PositiveOrZero @field:Schema(description = "原簇期望 lifecycle_version，精确 CAS") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "拆分原因，3 至 500 字") val reason: String
)
data class ClusterReopenRequest(
    @field:Positive @field:Schema(description = "证明复发的反馈 source ID；须通过数据范围与强复发证据校验") val recurrenceSourceId: Long,
    @field:PositiveOrZero @field:Schema(description = "已解决簇期望 lifecycle_version，精确 CAS") val expectedVersion: Long,
    @field:NotBlank @field:Schema(description = "UUID 幂等请求号；同号异命令返回冲突") val requestId: String,
    @field:Size(min=3,max=500) @field:Schema(description = "重开原因，3 至 500 字") val reason: String
)

data class ClusterConfirmedResponse(@field:Schema(description = "是否已确认；幂等重放仍返回 true") val confirmed: Boolean)
data class ClusterBatchReviewedResponse(@field:Schema(description = "已处理或幂等重放的命令数量，最大 200") val reviewed: Int)
data class ClusterCreatedResponse(@field:Schema(description = "创建或复用的问题簇 ID") val clusterId: Long)
data class ClusterRejectedResponse(@field:Schema(description = "是否已拒绝；幂等重放仍返回 true") val rejected: Boolean)
data class ClusterSkippedResponse(@field:Schema(description = "是否已跳过；幂等重放仍返回 true") val skipped: Boolean)
data class ClusterUnlinkedResponse(@field:Schema(description = "是否已解除 MAIN；幂等重放仍返回 true") val unlinked: Boolean)
data class ClusterStatusChangedResponse(@field:Schema(description = "是否已完成状态 CAS；幂等重放仍返回 true") val changed: Boolean)
data class ClusterMergedResponse(@field:Schema(description = "是否已原子合并；幂等重放仍返回 true") val merged: Boolean)
data class ClusterSplitResponse(@field:Schema(description = "按请求分区顺序返回的新问题簇 ID") val clusterIds: List<Long>)
data class ClusterReopenedResponse(@field:Schema(description = "是否已插入复发 MAIN 并重开；幂等重放仍返回 true") val reopened: Boolean)

@RestController
@RequestMapping("/api/v1/cluster")
class ClusterReviewController(private val service: ClusterReviewService, private val lifecycle: ClusterLifecycleService,
                              private val governance: ClusterReviewGovernance) {
    @GetMapping("/review/policy")
    fun policy() = ApiResponse.success(governance.policy())
    @GetMapping("/review")
    fun reviews(@RequestParam(required = false) priority: String?, @RequestParam(defaultValue = "1") page: Int,
                @RequestParam(defaultValue = "20") size: Int) = ApiResponse.success(service.reviews(priority, page, size))

    @PostMapping("/review/{sourceId}/confirm")
    fun confirm(@PathVariable @Positive sourceId: Long, @Valid @RequestBody r: ConfirmClusterRequest, auth: Authentication): ApiResponse<ClusterConfirmedResponse> {
        service.confirm(sourceId, r.clusterId, r.command(), auth.userId()); return ApiResponse.success(ClusterConfirmedResponse(true))
    }
    @PostMapping("/review/batch")
    fun batch(@Valid @RequestBody items: List<ClusterBatchReviewItem>, auth: Authentication): ApiResponse<ClusterBatchReviewedResponse> =
        ApiResponse.success(ClusterBatchReviewedResponse(service.reviewBatch(items, auth.userId())))
    @PostMapping("/review/create")
    fun create(@Valid @RequestBody r: CreateClusterRequest, auth: Authentication) =
        ApiResponse.success(ClusterCreatedResponse(service.create(r.sourceId, r.name, r.command(), auth.userId())))
    @PostMapping("/review/{sourceId}/reject")
    fun reject(@PathVariable @Positive sourceId: Long, @Valid @RequestBody r: ClusterReviewActionRequest, auth: Authentication): ApiResponse<ClusterRejectedResponse> {
        service.reject(sourceId, r.command(), auth.userId()); return ApiResponse.success(ClusterRejectedResponse(true))
    }
    @PostMapping("/review/{sourceId}/skip")
    fun skip(@PathVariable @Positive sourceId: Long, @Valid @RequestBody r: ClusterReviewActionRequest, auth: Authentication): ApiResponse<ClusterSkippedResponse> {
        service.skip(sourceId, r.command(), auth.userId()); return ApiResponse.success(ClusterSkippedResponse(true))
    }
    @PostMapping("/review/{sourceId}/unlink")
    fun unlink(@PathVariable @Positive sourceId: Long, @Valid @RequestBody r: ClusterReviewActionRequest, auth: Authentication): ApiResponse<ClusterUnlinkedResponse> {
        service.unlink(sourceId, r.command(), auth.userId()); return ApiResponse.success(ClusterUnlinkedResponse(true))
    }
    @PostMapping("/{clusterId}/status")
    fun status(@PathVariable @Positive clusterId: Long, @Valid @RequestBody r: ClusterStatusRequest, auth: Authentication): ApiResponse<ClusterStatusChangedResponse> {
        lifecycle.transition(clusterId, r.status, r.command(), auth.userId()); return ApiResponse.success(ClusterStatusChangedResponse(true))
    }
    @PostMapping("/{targetId}/merge")
    fun merge(@PathVariable @Positive targetId: Long, @Valid @RequestBody r: ClusterMergeRequest, auth: Authentication): ApiResponse<ClusterMergedResponse> {
        lifecycle.merge(targetId, r.sourceClusterIds, r.command(), auth.userId()); return ApiResponse.success(ClusterMergedResponse(true))
    }
    @PostMapping("/{clusterId}/split")
    fun split(@PathVariable @Positive clusterId: Long, @Valid @RequestBody r: ClusterSplitRequest, auth: Authentication): ApiResponse<ClusterSplitResponse> =
        ApiResponse.success(ClusterSplitResponse(lifecycle.split(clusterId, r.partitions.map { SplitPartition(it.name, it.sourceIds) }, r.command(), auth.userId())))
    @PostMapping("/{clusterId}/reopen")
    fun reopen(@PathVariable @Positive clusterId: Long, @Valid @RequestBody r: ClusterReopenRequest, auth: Authentication): ApiResponse<ClusterReopenedResponse> {
        lifecycle.reopen(clusterId, r.recurrenceSourceId, r.command(), auth.userId()); return ApiResponse.success(ClusterReopenedResponse(true))
    }
    private fun Authentication.userId() = (principal as WebPrincipal).userId
    private fun ConfirmClusterRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun CreateClusterRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun ClusterReviewActionRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun ClusterStatusRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun ClusterMergeRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun ClusterSplitRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
    private fun ClusterReopenRequest.command() = ClusterReviewCommand(expectedVersion, requestId, reason)
}
