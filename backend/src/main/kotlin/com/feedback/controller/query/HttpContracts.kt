package com.feedback.controller.query

import com.feedback.service.query.ApplicationFeedbackFilters
import com.feedback.service.query.CommonFeedbackFilters
import com.feedback.service.query.FeedbackListQuery
import com.feedback.service.query.analysis.ProblemAnalysisPage
import com.feedback.service.query.analysis.ProblemAnalysisPeriod
import com.feedback.service.query.analysis.ProblemAnalysisQuery
import com.feedback.service.query.analysis.ClusterDetailQuery
import com.feedback.service.query.analysis.ClusterFeedbackQuery
import com.feedback.service.query.FeedbackPageRequest
import com.feedback.service.query.FeedbackSortSpec
import com.feedback.service.query.JiraFeedbackFilters
import com.feedback.service.query.QualityFeedbackFilters
import com.feedback.service.query.RedblackFeedbackFilters
import com.feedback.service.query.StationFeedbackFilters
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime

/** 反馈列表与查询导出的公共筛选合同；字段均为显式兼容参数，不接受通用参数 Map。 */
open class FeedbackFilterRequest {
    @field:Size(max = 30) @field:Schema(
        description = "单一后台来源渠道；学习机聚合可改用 channels",
        allowableValues = ["SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"], example = "SDK"
    )
    var channel: String? = null
    @field:Size(max = 200) @field:Schema(description = "标题、描述或来源编号关键词，最长 200 字", example = "课程播放失败")
    var keyword: String? = null
    @field:Size(max = 30) @field:Schema(description = "兼容严重度筛选；反馈页面不展示该筛选")
    var severity: String? = null
    @field:Size(max = 30) @field:Schema(description = "兼容处理状态筛选；反馈页面不展示该筛选")
    var status: String? = null
    @field:Size(max = 200) @field:Schema(description = "兼容功能模块筛选；反馈页面不展示该筛选")
    var module: String? = null
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "来源语义时间下界，ISO-8601；可单独提供", example = "2026-08-01T00:00:00")
    var startTime: LocalDateTime? = null
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "来源语义时间上界，ISO-8601；可单独提供", example = "2026-08-31T23:59:59")
    var endTime: LocalDateTime? = null
    @field:Size(max = 5) @field:Schema(
        description = "后台来源渠道集合；只允许反馈五个来源码",
        allowableValues = ["SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"]
    )
    var channels: List<String> = emptyList()
    @field:Size(max = 20) @field:Schema(description = "兼容严重度集合；反馈页面不展示")
    var severities: List<String> = emptyList()
    @field:Size(max = 20) @field:Schema(description = "兼容处理状态集合；反馈页面不展示")
    var statuses: List<String> = emptyList()
    @field:Size(max = 100) @field:Schema(description = "兼容功能模块集合；反馈页面不展示")
    var modules: List<String> = emptyList()
    @field:Size(max = 100) @field:Schema(description = "兼容问题类型集合；反馈页面不展示")
    var problemTypes: List<String> = emptyList()
    @field:Size(max = 100) @field:Schema(description = "兼容可重现性集合；反馈页面不展示")
    var reproducibilities: List<String> = emptyList()
    @field:Size(max = 30) @field:Schema(description = "安全正文状态；反馈读取只允许 PASS")
    var maskingStatus: String? = null
    @field:Size(max = 30) @field:Schema(description = "兼容 AI 复核状态；不决定反馈数据能否读取")
    var aiReviewStatus: String? = null
    @field:Size(max = 100) @field:Schema(description = "省级范围筛选；仍受当前用户数据范围约束")
    var province: String? = null
    @field:Size(max = 100) @field:Schema(description = "城市范围筛选；仍受当前用户数据范围约束")
    var city: String? = null
    @field:Positive @field:Schema(description = "兼容负责人 ID 筛选；反馈页面不展示")
    var ownerId: Long? = null
    @field:Schema(description = "兼容只看本人筛选；与 ownerId、unassigned 互斥")
    var mine: Boolean = false
    @field:Schema(description = "兼容未分配筛选；与 ownerId、mine 互斥")
    var unassigned: Boolean = false
    @field:Schema(description = "兼容待交接筛选；反馈页面不展示")
    var handoffPending: Boolean = false
    @field:Pattern(regexp = "reportedAt|sourceUpdatedAt|platformUpdatedAt|severity|status|title")
    @field:Schema(description = "稳定排序字段", example = "reportedAt")
    var sortBy: String = "reportedAt"
    @field:Pattern(regexp = "(?i)asc|desc") @field:Schema(description = "排序方向", example = "desc")
    var sortDirection: String = "desc"

    @field:Size(max = 200) @field:Schema(description = "SDK 应用 ID 兼容筛选") var appId: String? = null
    @field:Size(max = 200) @field:Schema(description = "SDK 包名兼容筛选") var packageName: String? = null
    @field:Size(max = 200) @field:Schema(description = "SDK 设备型号模糊筛选") var deviceModel: String? = null
    @field:Size(max = 200) @field:Schema(description = "SDK 系统版本模糊筛选") var osVersion: String? = null
    @field:Size(max = 200) @field:Schema(description = "SDK 网络类型兼容筛选") var networkType: String? = null
    @field:Size(max = 200) @field:Schema(description = "来源应用版本兼容筛选") var appVersion: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 项目 Key 兼容筛选") var projectKey: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 编号兼容筛选") var issueKey: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 问题类型兼容筛选") var issueType: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 优先级兼容筛选") var priority: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 经办人兼容筛选") var assignee: String? = null
    @field:Size(max = 200) @field:Schema(description = "Jira 解决结果兼容筛选") var resolution: String? = null
    @field:Size(max = 200) @field:Schema(description = "质量平台报修区域模糊筛选") var repairRegion: String? = null
    @field:Size(max = 200) @field:Schema(description = "质量平台申请类型兼容筛选") var applicationType: String? = null
    @field:Pattern(regexp = "(?i)true|false|1|0") @field:Schema(description = "质量平台换机需求，兼容 true/false/1/0") var exchangeRequest: String? = null
    @field:Size(max = 200) @field:Schema(description = "导入业务批次号兼容筛选") var batchId: String? = null
    @field:Size(max = 200) @field:Schema(description = "红黑榜来源平台兼容筛选") var sourcePlatform: String? = null
    @field:Size(max = 200) @field:Schema(description = "红黑榜情感类型兼容筛选") var emotionType: String? = null
    @field:Size(max = 200) @field:Schema(description = "渠道专属关键词兼容筛选") var channelKeyword: String? = null
    @field:Size(max = 200) @field:Schema(description = "红黑榜类型兼容筛选") var listType: String? = null
    @field:Size(max = 200) @field:Schema(description = "旧反馈平台产品 ID 兼容筛选") var productId: String? = null
    @field:Size(max = 200) @field:Schema(description = "旧反馈平台子渠道兼容筛选") var sourceSubChannel: String? = null
    @field:Size(max = 200) @field:Schema(description = "旧反馈平台 API 版本兼容筛选") var apiVersion: String? = null
    @field:Pattern(regexp = "(?i)MISSING|COMPLETE") @field:Schema(description = "版本事实完整性兼容筛选") var versionCompleteness: String? = null
    @field:Pattern(regexp = "(?i)ALL|UNRESOLVED|RESOLVED") @field:Schema(description = "Jira 解决状态兼容筛选") var sourceResolutionState: String? = null

    fun toQuery(page: Int, size: Int) = FeedbackListQuery(
        common = CommonFeedbackFilters(
            channel = channel, keyword = keyword, severity = severity, status = status, module = module,
            startTime = startTime, endTime = endTime,
            channels = channels.filter(String::isNotBlank), severities = severities.filter(String::isNotBlank),
            statuses = statuses.filter(String::isNotBlank), modules = modules.filter(String::isNotBlank),
            problemTypes = problemTypes.filter(String::isNotBlank),
            reproducibilities = reproducibilities.filter(String::isNotBlank),
            maskingStatus = maskingStatus, aiReviewStatus = aiReviewStatus, province = province, city = city,
            ownerId = ownerId, mine = mine, unassigned = unassigned, handoffPending = handoffPending,
            batchId = batchId, sourcePlatform = sourcePlatform
        ),
        page = FeedbackPageRequest(page = page, size = size),
        sort = FeedbackSortSpec(sortBy = sortBy, direction = sortDirection),
        application = ApplicationFeedbackFilters(
            appId = appId, packageName = packageName, deviceModel = deviceModel, osVersion = osVersion,
            networkType = networkType, appVersion = appVersion, versionCompleteness = versionCompleteness
        ),
        jira = JiraFeedbackFilters(
            projectKey = projectKey, issueKey = issueKey, issueType = issueType, priority = priority,
            assignee = assignee, resolution = resolution, resolutionState = sourceResolutionState
        ),
        quality = QualityFeedbackFilters(
            repairRegion = repairRegion, applicationType = applicationType, exchangeRequest = exchangeRequest
        ),
        redblack = RedblackFeedbackFilters(
            emotionType = emotionType, channelKeyword = channelKeyword, listType = listType
        ),
        station = StationFeedbackFilters(
            productId = productId, sourceSubChannel = sourceSubChannel, apiVersion = apiVersion
        )
    )
}

@Schema(description = "反馈列表查询")
class FeedbackListRequest : FeedbackFilterRequest() {
    @field:Min(1) @field:Schema(description = "页码，从 1 开始", example = "1") var page: Int = 1
    @field:Min(1) @field:Max(100) @field:Schema(description = "每页条数，范围 1 至 100", example = "20") var size: Int = 20
    fun toQuery(): FeedbackListQuery = toQuery(page, size)
}

@Schema(description = "反馈查询导出")
class FeedbackExportQueryRequest : FeedbackFilterRequest() {
    @field:Size(max = 5000) @field:Schema(description = "显式反馈 ID；非空时忽略查询筛选并整体校验数据范围")
    var ids: List<Long> = emptyList()
    fun toQuery(): FeedbackListQuery = toQuery(1, 100)
}

@Schema(description = "反馈 ID 选择")
data class FeedbackSelectionRequest(
    @field:Size(min = 1, max = 5000) @field:Schema(description = "反馈 ID，最多 5000 个；任一缺失或越权整体拒绝")
    val ids: List<@Positive Long>
)

@Schema(description = "反馈搜索建议查询")
class SearchSuggestionRequest {
    @field:NotBlank @field:Size(min = 2, max = 200)
    @field:Schema(description = "安全投影搜索词；去除首尾空格后至少 2 字，最多 200 字", example = "课程崩溃")
    var q: String = ""
    @field:Min(1) @field:Max(20) @field:Schema(description = "返回建议上限，默认 10，范围 1 至 20", example = "10")
    var limit: Int = 10
}

@Schema(description = "反馈问题深度分析查询")
class ProblemAnalysisRequest {
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "分析开始时间，ISO 本地日期时间；必须与结束时间同时提供，两者均为空表示全部历史", example = "2026-08-01T00:00:00")
    var startTime: LocalDateTime? = null
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "分析结束时间，ISO 本地日期时间且包含边界；必须与开始时间同时提供", example = "2026-08-31T23:59:59")
    var endTime: LocalDateTime? = null
    @field:Min(1) @field:Schema(description = "全部问题簇页码，从 1 开始；与跨渠道页码互不影响", example = "1") var allPage: Int = 1
    @field:Min(1) @field:Max(100) @field:Schema(description = "全部问题簇每页条数，默认 20，范围 1 至 100", example = "20") var allSize: Int = 20
    @field:Min(1) @field:Schema(description = "跨渠道问题簇页码，从 1 开始；与全部问题簇页码互不影响", example = "1") var crossChannelPage: Int = 1
    @field:Min(1) @field:Max(100) @field:Schema(description = "跨渠道问题簇每页条数，默认 20，范围 1 至 100", example = "20") var crossChannelSize: Int = 20

    fun toQuery(): ProblemAnalysisQuery = ProblemAnalysisQuery(
        period = ProblemAnalysisPeriod(startTime, endTime),
        allClusters = ProblemAnalysisPage(allPage, allSize),
        crossChannelClusters = ProblemAnalysisPage(crossChannelPage, crossChannelSize)
    )
}

@Schema(description = "反馈问题簇时间范围")
open class ClusterTimeRangeRequest {
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "统计开始时间，ISO 本地日期时间；必须与结束时间同时提供，两者均为空表示全部历史", example = "2026-08-01T00:00:00")
    var startTime: LocalDateTime? = null
    @field:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    @field:Schema(description = "统计结束时间，ISO 本地日期时间且包含边界；必须与开始时间同时提供", example = "2026-08-31T23:59:59")
    var endTime: LocalDateTime? = null

    fun toQuery(clusterId: Long): ClusterDetailQuery = ClusterDetailQuery(
        clusterId,
        ProblemAnalysisPeriod(startTime, endTime)
    )
}

@Schema(description = "反馈问题簇内反馈查询")
class ClusterFeedbackRequest : ClusterTimeRangeRequest() {
    @field:Min(1) @field:Schema(description = "页码，从 1 开始", example = "1") var page: Int = 1
    @field:Min(1) @field:Max(100) @field:Schema(description = "每页条数，默认 20，范围 1 至 100", example = "20") var size: Int = 20

    fun toFeedbackQuery(clusterId: Long): ClusterFeedbackQuery = ClusterFeedbackQuery(
        clusterId,
        ProblemAnalysisPage(page, size),
        ProblemAnalysisPeriod(startTime, endTime)
    )
}
