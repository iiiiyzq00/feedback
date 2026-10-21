package com.feedback.service.query

import java.time.LocalDateTime

/**
 * 反馈列表的应用层查询合同。
 *
 * HTTP 层只负责把兼容参数映射为这些具名组合；条件归一化、权限收敛和持久化条件仍由应用层负责。
 */
data class FeedbackListQuery(
    val common: CommonFeedbackFilters = CommonFeedbackFilters(),
    val page: FeedbackPageRequest = FeedbackPageRequest(),
    val sort: FeedbackSortSpec = FeedbackSortSpec(),
    val application: ApplicationFeedbackFilters = ApplicationFeedbackFilters(),
    val jira: JiraFeedbackFilters = JiraFeedbackFilters(),
    val quality: QualityFeedbackFilters = QualityFeedbackFilters(),
    val redblack: RedblackFeedbackFilters = RedblackFeedbackFilters(),
    val station: StationFeedbackFilters = StationFeedbackFilters()
)

/** 跨渠道公共筛选；不包含分页、排序或只对单一渠道成立的字段。 */
data class CommonFeedbackFilters(
    val channel: String? = null,
    val keyword: String? = null,
    val severity: String? = null,
    val status: String? = null,
    val module: String? = null,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val channels: List<String> = emptyList(),
    val severities: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val problemTypes: List<String> = emptyList(),
    val reproducibilities: List<String> = emptyList(),
    val maskingStatus: String? = null,
    val aiReviewStatus: String? = null,
    val province: String? = null,
    val city: String? = null,
    val ownerId: Long? = null,
    val mine: Boolean = false,
    val unassigned: Boolean = false,
    val handoffPending: Boolean = false,
    val batchId: String? = null,
    val sourcePlatform: String? = null
)

data class FeedbackPageRequest(val page: Int = 1, val size: Int = 20)

data class FeedbackSortSpec(val sortBy: String = "reportedAt", val direction: String = "desc")

/** SDK 与学习机来源共享的应用、设备和版本事实筛选。 */
data class ApplicationFeedbackFilters(
    val appId: String? = null,
    val packageName: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val networkType: String? = null,
    val appVersion: String? = null,
    val versionCompleteness: String? = null
)

data class JiraFeedbackFilters(
    val projectKey: String? = null,
    val issueKey: String? = null,
    val issueType: String? = null,
    val priority: String? = null,
    val assignee: String? = null,
    val resolution: String? = null,
    val resolutionState: String? = null
)

data class QualityFeedbackFilters(
    val repairRegion: String? = null,
    val applicationType: String? = null,
    val exchangeRequest: String? = null
)

data class RedblackFeedbackFilters(
    val emotionType: String? = null,
    val channelKeyword: String? = null,
    val listType: String? = null
)

data class StationFeedbackFilters(
    val productId: String? = null,
    val sourceSubChannel: String? = null,
    val apiVersion: String? = null
)
