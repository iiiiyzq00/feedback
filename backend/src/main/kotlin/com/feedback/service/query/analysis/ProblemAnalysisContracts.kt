package com.feedback.service.query.analysis

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "问题簇维度统计")
data class ProblemClusterDimensionResponse(
    @field:Schema(description = "维度值；应用维度时为应用名", example = "课堂助手") val application: String? = null,
    @field:Schema(description = "维度值；区域维度时为区域名或代码", example = "北京市") val region: String? = null,
    @field:Schema(description = "该维度反馈记录数", example = "12") val count: Long
)

@Schema(description = "反馈问题分析问题簇行")
data class ProblemClusterItemResponse(
    @field:Schema(description = "问题簇 ID", example = "42") val clusterId: Long,
    @field:Schema(description = "面向产品的问题现象，不包含原始敏感正文", example = "课程播放过程中画面卡顿") val phenomenon: String,
    @field:Schema(description = "当前权限和时间范围内反馈记录数，单位：条", example = "128") val recordCount: Long,
    @field:Schema(description = "不可逆指纹去重用户数，单位：人；无用户身份事实时为 null", example = "37") val userCount: Long?,
    @field:Schema(description = "设备序列号不可逆指纹去重数，单位：台", example = "42") val deviceCount: Long,
    @field:Schema(description = "影响应用前五项") val affectedApplications: List<ProblemClusterDimensionResponse>,
    @field:Schema(description = "首次来源语义时间，ISO 本地日期时间", example = "2026-08-01T09:30:00") val firstOccurredAt: LocalDateTime?,
    @field:Schema(description = "最近来源语义时间，ISO 本地日期时间", example = "2026-08-31T18:20:00") val lastOccurredAt: LocalDateTime?,
    @field:Schema(description = "通过安全门禁的附件数，单位：个", example = "12") val attachmentCount: Long,
    @field:Schema(description = "通过安全门禁的截图数，单位：个", example = "8") val screenshotCount: Long,
    @field:Schema(description = "通过安全门禁的日志数，单位：个", example = "3") val logCount: Long,
    @field:Schema(description = "涉及区域前五项") val regions: List<ProblemClusterDimensionResponse>
)

@Schema(description = "反馈问题簇分页；全部问题簇与跨渠道问题簇各自维护页码和总数")
data class ProblemClusterPageResponse(
    @field:Schema(description = "当前页问题簇") val items: List<ProblemClusterItemResponse>,
    @field:Schema(description = "当前权限和时间范围内问题簇总数", example = "73") val total: Long,
    @field:Schema(description = "页码，从 1 开始", example = "1") val page: Int,
    @field:Schema(description = "每页条数，范围 1 至 100", example = "20") val size: Int
)

@Schema(description = "反馈问题深度分析响应")
data class ProblemAnalysisResponse(
    @field:Schema(description = "全部可见问题簇，独立分页") val allClusters: ProblemClusterPageResponse,
    @field:Schema(description = "至少涉及两个逻辑渠道的问题簇，独立分页") val crossChannelClusters: ProblemClusterPageResponse,
    @field:Schema(description = "显式分析开始时间；全历史时为 null", example = "2026-08-01T00:00:00") val startTime: LocalDateTime?,
    @field:Schema(description = "显式分析结束时间；全历史时为 null", example = "2026-08-31T23:59:59") val endTime: LocalDateTime?,
    @field:Schema(description = "响应生成时间，ISO 本地日期时间", example = "2026-09-01T09:30:00") val generatedAt: LocalDateTime,
    @field:Schema(description = "指标口径版本", example = "feedback-problem-analysis-v1") val metricVersion: String = "feedback-problem-analysis-v1"
)

@Schema(description = "反馈问题簇详情")
data class ClusterDetailResponse(
    @field:Schema(description = "问题簇 ID", example = "42") val id: Long,
    @field:Schema(description = "问题簇内部名称") val name: String,
    @field:Schema(description = "面向产品的问题现象") val canonicalSymptom: String,
    @field:Schema(description = "功能模块") val functionModule: String?,
    @field:Schema(description = "问题分类") val issueCategory: String?,
    @field:Schema(description = "问题簇生命周期状态") val status: String,
    @field:Schema(description = "生命周期乐观锁版本") val lifecycleVersion: Long,
    @field:Schema(description = "合并目标问题簇 ID") val mergedIntoClusterId: Long?,
    @field:Schema(description = "拆分来源问题簇 ID") val splitFromClusterId: Long?,
    @field:Schema(description = "净化后的状态原因") val statusReason: String?,
    @field:Schema(description = "当前范围反馈记录数，单位：条", example = "128") val totalFeedback: Long,
    @field:Schema(description = "当前范围逻辑渠道数；SDK 与学习机站端合并计数", example = "3") val channelCount: Long,
    @field:Schema(description = "最近来源语义时间") val latestTime: LocalDateTime?,
    @field:Schema(description = "首次来源语义时间") val firstTime: LocalDateTime?,
    @field:Schema(description = "首末日期之间的自然日数，单位：天", example = "31") val activeDays: Long,
    @field:Schema(description = "不可逆指纹去重用户数，单位：人；无事实时为 null", example = "37") val uniqueUsers: Long?,
    @field:Schema(description = "设备序列号不可逆指纹去重数，单位：台", example = "42") val uniqueDevices: Long,
    @field:Schema(description = "学习机逻辑渠道反馈数") val stationCount: Long,
    @field:Schema(description = "Jira 反馈数") val jiraCount: Long,
    @field:Schema(description = "质量平台反馈数") val qualityCount: Long,
    @field:Schema(description = "红黑榜反馈数") val redblackCount: Long
)

@Schema(description = "反馈问题簇内反馈行")
data class ClusterFeedbackItemResponse(
    @field:Schema(description = "反馈来源 ID", example = "10001") val id: Long,
    @field:Schema(description = "渠道原始记录业务号") val sourceRecordId: String?,
    @field:Schema(description = "已脱敏问题内容") val issueContent: String,
    @field:Schema(description = "来源渠道，小写枚举", allowableValues = ["sdk", "station_feedback", "jira", "quality", "redblack"], example = "jira") val sourceChannel: String,
    @field:Schema(description = "故障或发布时间，按来源语义确定", example = "2026-08-31T18:20:00") val reportedAt: LocalDateTime?,
    @field:Schema(description = "可按权限查验的信息类型，逗号分隔；不含身份值") val controlledInfoTypes: String?,
    @field:Schema(description = "安全附件数") val attachmentCount: Int,
    @field:Schema(description = "安全截图数") val screenshotCount: Int,
    @field:Schema(description = "安全日志数") val logCount: Int,
    @field:Schema(description = "安全视频数") val videoCount: Int,
    @field:Schema(description = "程序归簇方式") val decisionType: String?,
    @field:Schema(description = "程序相似度，范围 0 至 1") val similarity: java.math.BigDecimal?,
    @field:Schema(description = "净化后的程序归簇原因") val decisionReason: String?,
    @field:Schema(description = "活动模型快照") val aiModel: String?,
    @field:Schema(description = "模型置信度，范围 0 至 1") val aiConfidence: java.math.BigDecimal?,
    @field:Schema(description = "模型建议动作") val aiAction: String?,
    @field:Schema(description = "净化后的模型解释") val aiExplanation: String?
)

@Schema(description = "反馈问题簇内安全反馈分页")
data class ClusterFeedbackPageResponse(
    @field:Schema(description = "当前页反馈，默认每页 20 条") val items: List<ClusterFeedbackItemResponse>,
    @field:Schema(description = "当前范围反馈总数，单位：条", example = "128") val total: Long,
    @field:Schema(description = "页码，从 1 开始", example = "1") val page: Int,
    @field:Schema(description = "每页条数，默认 20，范围 1 至 100", example = "20") val size: Int
)
