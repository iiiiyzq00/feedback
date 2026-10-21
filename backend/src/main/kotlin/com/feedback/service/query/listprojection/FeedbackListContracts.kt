package com.feedback.service.query.listprojection

import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.FieldScopedResponse
import com.feedback.service.query.detail.FeedbackChannelMetaResponse
import com.feedback.service.query.detail.FeedbackDiagnosticAvailabilityResponse
import com.feedback.service.query.FeedbackDiagnosticItemResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "反馈渠道列表响应；整个请求固定使用同一个已验证 ACTIVE generation")
data class FeedbackListResponse(
    @field:Schema(description = "当前页安全投影记录") val records: List<FeedbackListRowResponse>,
    @field:Schema(description = "当前筛选条件命中的总记录数", example = "125") val total: Long,
    @field:Schema(description = "页码，从 1 开始", example = "1") val page: Int,
    @field:Schema(description = "每页记录数，范围 1 至 100", example = "20") val size: Int,
    @field:Schema(description = "与列表相同筛选口径的统计事实") val stats: FeedbackListStatsResponse
)

@Schema(description = "反馈渠道列表单行；不包含原始敏感值、附件正文、存储路径或长期外链")
data class FeedbackListRowResponse(
    @field:Schema(description = "平台反馈 ID", example = "1024") val id: Long,
    @field:Schema(
        description = "小写来源渠道码",
        allowableValues = ["sdk", "station_feedback", "jira", "quality", "redblack"], example = "jira"
    ) val sourceChannel: String,
    @field:Schema(description = "来源系统记录 ID", example = "SPFB-1024") val sourceRecordId: String,
    @field:Schema(description = "安全标题；缺失时使用安全正文或平台 ID 兜底") val title: String,
    @field:Schema(description = "规则脱敏通过后的安全正文") val description: String?,
    @field:Schema(description = "标准化功能模块；缺失时为 UNKNOWN") val module: String,
    @field:Schema(description = "问题类型；来源未提供时为空") val problemType: String?,
    @field:Schema(description = "问题分类；缺失时为 UNKNOWN") val issueCategory: String,
    @field:Schema(description = "展示严重度；人工值优先于 AI 建议") val severity: String?,
    @field:Schema(description = "严重度是否仅来自 AI 建议") val severityProvisional: Boolean,
    @field:Schema(description = "严重度复核状态") val severityReviewStatus: String?,
    @field:Schema(description = "用户情感枚举") val emotion: String?,
    @field:Schema(description = "统一工作流状态") val status: String,
    @field:Schema(description = "统一分发状态") val distributionStatus: String,
    @field:Schema(description = "故障发生或来源发布时间，Asia/Shanghai") val reportedAt: LocalDateTime?,
    @field:Schema(description = "来源更新时间，Asia/Shanghai") val sourceUpdatedAt: LocalDateTime?,
    @field:Schema(description = "投影所依据的平台更新时间，Asia/Shanghai") val platformUpdatedAt: LocalDateTime?,
    @field:Schema(description = "平台接入时间，Asia/Shanghai") val ingestTime: LocalDateTime?,
    @field:Schema(description = "应用版本；来源未提供时为空") val appVersion: String?,
    @field:Schema(description = "来源省份编码或名称；来源未提供时为空") val province: String?,
    @field:Schema(description = "来源城市编码或名称；来源未提供时为空") val city: String?,
    @field:Schema(description = "当前负责人用户 ID；未分配时为空") val ownerId: Long?,
    @field:Schema(description = "当前页批量实时解析的负责人显示名；未分配或账号缺失时为空") val ownerName: String?,
    @field:Schema(description = "安全附件总数") val attachmentCount: Int,
    @field:Schema(description = "安全图片或截图数量") val screenshotCount: Int,
    @field:Schema(description = "日志附件数量；按当前角色权限在页面内查看") val logCount: Int,
    @field:Schema(description = "安全视频或录屏数量") val videoCount: Int,
    @field:Schema(description = "当前渠道的具名安全扩展事实") val channelMeta: FeedbackChannelMetaResponse?,
    @field:Schema(description = "关键信息和日志的类型可用性，不含正文") val diagnosticAvailability: FeedbackDiagnosticAvailabilityResponse,
    @field:Schema(description = "当前角色可直接查看的非日志关键信息；技术角色为原文，产品角色为局部隐藏值")
    val keyDiagnostics: List<FeedbackDiagnosticItemResponse>,
    @field:Schema(description = "关键信息展示级别", allowableValues = ["RAW", "PARTIALLY_HIDDEN"])
    val diagnosticsVisibility: String,
    @field:Schema(description = "学习机聚合入口展示的应用名称") val learningMachineAppName: String?,
    @field:Schema(description = "红黑榜发布时间；其他渠道为空") val publishedAt: LocalDateTime?,
    @field:Schema(description = "SDK 数据是否来自模拟导入；非 SDK 为空") val sdkDataType: String?,
    @field:Schema(description = "红黑榜标题与标准情感是否存在确定性语义冲突") val sentimentConflict: Boolean,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "反馈列表统计；所有值来自同一安全投影和同一筛选口径")
data class FeedbackListStatsResponse(
    @field:Schema(description = "命中记录总数") val total: Long,
    @field:Schema(description = "最近业务时间，Asia/Shanghai") val latestTime: LocalDateTime?,
    @field:Schema(description = "最近来源更新时间，Asia/Shanghai") val latestUpdateTime: LocalDateTime?,
    @field:Schema(description = "最近接入时间，Asia/Shanghai") val latestIngestTime: LocalDateTime?,
    @field:Schema(description = "命中的物理来源渠道数量") val channelCount: Long,
    @field:Schema(description = "未分配记录数") val unassignedCount: Long,
    @field:Schema(description = "活动工作流记录数") val activeCount: Long,
    @field:Schema(description = "已解决或关闭记录数") val resolvedCount: Long,
    @field:Schema(description = "存在至少一个安全证据的记录数") val withEvidenceCount: Long,
    @field:Schema(description = "正向情感记录数") val positiveCount: Long,
    @field:Schema(description = "负向情感记录数") val negativeCount: Long,
    @field:Schema(description = "SDK 最近五分钟接入记录数；非 SDK 口径为 0") val newRecords5m: Long
)

data class FeedbackExportAuditContext(
    val requestId: String,
    val clientIp: String?
)
