package com.feedback.service.query

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDateTime

@Schema(description = "反馈搜索建议")
data class SearchSuggestionItemResponse(
    @field:Schema(description = "自动补全组件使用的安全值", example = "课程播放卡顿") val value: String,
    @field:Schema(description = "已脱敏反馈标题", example = "课程播放卡顿") val title: String,
    @field:Schema(description = "功能模块；无值时为 null") val description: String?,
    @field:Schema(description = "来源渠道标签", allowableValues = ["SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"], example = "JIRA") val typeLabel: String,
    @field:Schema(description = "前端标签颜色；当前固定为 primary") val typeColor: String = "primary",
    @field:Schema(description = "反馈详情站内路径，不返回外部 URL", example = "/channel/10001") val path: String,
    @field:Schema(description = "反馈来源 ID", example = "10001") val id: Long
)

@Schema(description = "反馈搜索建议的渠道候选计数")
data class SearchSuggestionChannelCountResponse(
    @field:Schema(description = "来源渠道", example = "JIRA") val channel: String,
    @field:Schema(description = "最多 100 条安全候选中的渠道数量") val count: Int
)

@Schema(description = "反馈搜索建议响应")
data class SearchSuggestionResponse(
    @field:Schema(description = "最多 20 条建议") val items: List<SearchSuggestionItemResponse>,
    @field:Schema(description = "首批最多 100 条安全候选中的具名渠道计数；不是全量分渠道统计") val channelCounts: List<SearchSuggestionChannelCountResponse>,
    @field:Schema(description = "符合列表条件的安全投影全量总数；可能大于渠道计数之和") val total: Long,
    @field:Schema(description = "去除首尾空格后的查询词") val query: String
)

@Schema(description = "反馈同簇相关反馈")
data class RelatedFeedbackItemResponse(
    @field:Schema(description = "反馈来源 ID", example = "10002") val id: Long,
    @field:Schema(description = "来源渠道，小写枚举", allowableValues = ["sdk", "station_feedback", "jira", "quality", "redblack"], example = "jira") val sourceChannel: String,
    @field:Schema(description = "渠道原始记录业务号") val sourceRecordId: String?,
    @field:Schema(description = "安全投影正文") val feedbackContent: String,
    @field:Schema(description = "故障或发布时间，按来源语义确定", example = "2026-08-31T18:20:00") val occurredAt: LocalDateTime?,
    @field:Schema(description = "归簇相似度，范围 0 至 1", example = "0.91") val similarity: BigDecimal?,
    @field:Schema(description = "净化后的归簇原因") val decisionReason: String?,
    @field:Schema(description = "旧非反馈解除操作兼容字段；投影路径无值") val decisionVersion: String? = null,
    @field:Schema(description = "旧非反馈解除操作兼容字段；投影路径无值") val reviewStatus: String? = null,
    @field:Schema(description = "旧非反馈解除操作兼容字段；投影路径无值") val reviewVersion: Long? = null,
    @field:Schema(description = "问题簇 ID", example = "42") val clusterId: Long
)

@Schema(description = "反馈同簇相关反馈分页响应")
data class RelatedFeedbackPageResponse(
    @field:Schema(description = "当前页相关反馈；均与当前反馈同簇且不含当前反馈自身") val records: List<RelatedFeedbackItemResponse>,
    @field:Schema(description = "不含当前反馈的同簇总数，单位：条", example = "27") val total: Long,
    @field:Schema(description = "页码，从 1 开始", example = "1") val page: Int,
    @field:Schema(description = "每页条数，默认 10，范围 1 至 50", example = "10") val size: Int
)

@Schema(description = "反馈按权限展示的诊断信息项")
data class FeedbackDiagnosticItemResponse(
    @field:Schema(description = "诊断类型；内置值包括联系方式、账户上下文、私有文件清单和应用日志，未知 SDK key 按大写原值返回", example = "APPLICATION_LOG") val type: String,
    @field:Schema(description = "非日志项按角色返回原文或局部隐藏值；日志项仅为安全元数据说明，不是日志正文") val value: String?,
    @field:Schema(description = "事实安全来源", allowableValues = ["ENCRYPTED_VAULT", "SOURCE_REFERENCE", "AUTHENTICATED_SNAPSHOT", "ENCRYPTED_DIAGNOSTIC_VAULT", "GOVERNED_ATTACHMENT"], example = "GOVERNED_ATTACHMENT") val source: String,
    @field:Schema(description = "日志附件平台 ID；非日志项为 null", example = "2048") val attachmentId: Long? = null,
    @field:Schema(description = "来源附件业务号；来源未提供或非日志项时为 null") val sourceAttachmentId: String? = null,
    @field:Schema(description = "日志附件大小，单位：字节；来源未知或非日志项时为 null", example = "4096") val fileSizeBytes: Long? = null,
    @field:Schema(description = "日志附件 MIME 类型；来源未知或非日志项时为 null", example = "text/plain") val mimeType: String? = null,
    @field:Schema(description = "是否在查询时刻通过全部安全检查；非日志项为 null，真实查看仍会实时复核") val available: Boolean? = null,
    @field:Schema(description = "附件安全状态；非日志项为 null", example = "VERIFIED") val securityStatus: String? = null,
    @field:Schema(description = "附件完整性状态；非日志项为 null", example = "VERIFIED") val integrityStatus: String? = null,
    @field:Schema(description = "附件恶意软件扫描状态；非日志项为 null", example = "CLEAN") val malwareScanStatus: String? = null,
    @field:Schema(description = "附件内容审核状态；非日志项为 null", example = "PASS") val contentReviewStatus: String? = null
)

@Schema(description = "反馈诊断查看响应")
data class FeedbackDiagnosticsResponse(
    @field:Schema(description = "反馈来源 ID", example = "1024") val sourceId: Long,
    @field:Schema(description = "诊断信息；任何解密、解析或审计失败均不返回部分结果") val diagnostics: List<FeedbackDiagnosticItemResponse>,
    @field:Schema(description = "本次非日志诊断值的展示级别", allowableValues = ["RAW", "PARTIALLY_HIDDEN"], example = "PARTIALLY_HIDDEN") val contentVisibility: String,
    @field:Schema(description = "响应禁止缓存；固定为 false") val responseCacheable: Boolean = false,
    @field:Schema(description = "信息展示限制") val usageRestriction: String = "反馈按技术/产品角色直接查看；页面关闭后立即清除"
)
