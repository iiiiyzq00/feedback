package com.feedback.application.port.feedback.list

import java.time.LocalDateTime

/**
 * 反馈列表安全投影的持久化读取端口。
 *
 * 端口使用不可变应用快照和应用查询条件；基础设施适配器负责与 MyBatis PO、Criteria 相互转换。
 * 实现必须保持单表投影查询和批量负责人解析，不得引入逐行查询。
 */
interface FeedbackListReadPort {
    fun page(generationId: Long, criteria: FeedbackListCriteria, limit: Int, offset: Long): List<FeedbackListRecord>
    fun exportRows(generationId: Long, criteria: FeedbackListCriteria, limit: Int): List<FeedbackListRecord>
    fun rowsByIds(generationId: Long, criteria: FeedbackListCriteria, sourceIds: List<Long>): List<FeedbackListRecord>
    fun stats(generationId: Long, criteria: FeedbackListCriteria): FeedbackListStats
    fun owners(ownerIds: List<Long>): Map<Long, String>
}

/** 已归一化并完成权限求交的应用层列表条件；不包含 MyBatis 参数或 SQL 约定。 */
data class FeedbackListCriteria(
    val allowedChannels: List<String>, val scopeModules: List<String>? = null, val scopeRegions: List<String>? = null,
    val channels: List<String>? = null, val keyword: String? = null, val severities: List<String>? = null,
    val statuses: List<String>? = null, val modules: List<String>? = null, val problemTypes: List<String>? = null,
    val reproducibilities: List<String>? = null, val aiReviewStatus: String? = null,
    val province: String? = null, val city: String? = null,
    val ownerId: Long? = null, val unassigned: Boolean = false, val handoffPending: Boolean = false,
    val startTime: LocalDateTime? = null, val endTime: LocalDateTime? = null,
    val appId: String? = null, val packageName: String? = null, val deviceModel: String? = null,
    val osVersion: String? = null, val networkType: String? = null, val appVersion: String? = null,
    val projectKey: String? = null, val issueKey: String? = null, val issueType: String? = null,
    val priority: String? = null, val assignee: String? = null, val resolution: String? = null,
    val repairRegion: String? = null, val applicationType: String? = null, val exchangeRequest: Boolean? = null,
    val importBatchCode: String? = null, val sourcePlatform: String? = null, val emotionType: String? = null,
    val channelKeyword: String? = null, val listType: String? = null, val productId: String? = null,
    val sourceSubChannel: String? = null, val apiVersion: String? = null,
    val versionCompleteness: String? = null, val sourceResolutionState: String? = null,
    val sortBy: String = "reportedAt", val sortDirection: String = "desc"
)

/** 列表用例可消费的不可变安全投影快照；不暴露可变持久化对象。 */
data class FeedbackListRecord(
    val sourceId: Long = 0,
    val sourceChannel: String = "",
    val sourceRecordId: String = "",
    val appVersion: String? = null,
    val packageName: String? = null,
    val provinceCode: String? = null,
    val cityCode: String? = null,
    val importBatchId: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val functionModule: String? = null,
    val problemType: String? = null,
    val issueCategory: String? = null,
    val severityLevel: String? = null,
    val aiSeverity: String? = null,
    val aiReviewStatus: String? = null,
    val userSentiment: String? = null,
    val processingStatus: String = "NEW",
    val distributionStatus: String = "UNASSIGNED",
    val ownerUserId: Long? = null,
    val reportedAt: LocalDateTime? = null,
    val sourceUpdateTime: LocalDateTime? = null,
    val platformUpdateTime: LocalDateTime? = null,
    val ingestTime: LocalDateTime? = null,
    val attachmentCount: Int = 0,
    val screenshotCount: Int = 0,
    val logCount: Int = 0,
    val videoCount: Int = 0,
    val channelAppId: String? = null,
    val channelPackageName: String? = null,
    val stationAppId: String? = null,
    val channelMetaJson: String? = null,
    val diagnosticTypes: String? = null
)

data class FeedbackListStats(
    val total: Long = 0,
    val latestTime: LocalDateTime? = null,
    val latestUpdateTime: LocalDateTime? = null,
    val latestIngestTime: LocalDateTime? = null,
    val channelCount: Long = 0,
    val unassignedCount: Long = 0,
    val activeCount: Long = 0,
    val resolvedCount: Long = 0,
    val withEvidenceCount: Long = 0,
    val positiveCount: Long = 0,
    val negativeCount: Long = 0,
    val newRecords5m: Long = 0
)
