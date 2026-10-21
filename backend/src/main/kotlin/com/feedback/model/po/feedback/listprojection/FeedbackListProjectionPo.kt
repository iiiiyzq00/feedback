package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListProjectionPo(
    var generationId: Long = 0,
    var sourceId: Long = 0,
    var sourceChannel: String = "",
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var packageName: String? = null,
    var provinceCode: String? = null,
    var cityCode: String? = null,
    var importBatchId: Long? = null,
    var importBatchCode: String? = null,
    var jiraImportBatchCodes: String? = null,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var problemType: String? = null,
    var issueCategory: String? = null,
    var severityLevel: String? = null,
    var aiSeverity: String? = null,
    var aiReviewStatus: String? = null,
    var userSentiment: String? = null,
    var reproducibility: String? = null,
    var processingStatus: String = "NEW",
    var distributionStatus: String = "UNASSIGNED",
    var ownerUserId: Long? = null,
    var handoffPending: Boolean = false,
    var faultOccurredAt: LocalDateTime? = null,
    var sourceEventTime: LocalDateTime? = null,
    var reportedAt: LocalDateTime? = null,
    var faultTimePrecision: String? = null,
    var faultTimeSource: String? = null,
    var faultTimeResolution: String? = null,
    var sourceUpdateTime: LocalDateTime? = null,
    var platformUpdateTime: LocalDateTime? = null,
    var ingestTime: LocalDateTime? = null,
    var attachmentCount: Int = 0,
    var screenshotCount: Int = 0,
    var logCount: Int = 0,
    var videoCount: Int = 0,
    var channelAppId: String? = null,
    var channelPackageName: String? = null,
    var channelDeviceModel: String? = null,
    var channelOsVersion: String? = null,
    var channelNetworkType: String? = null,
    var jiraKey: String? = null,
    var jiraIssueType: String? = null,
    var jiraStatus: String? = null,
    var jiraPriority: String? = null,
    var jiraProjectKey: String? = null,
    var jiraAssignee: String? = null,
    var jiraResolution: String? = null,
    var jiraResolvedAt: LocalDateTime? = null,
    var qualityRepairRegion: String? = null,
    var qualityApplicationType: String? = null,
    var qualityExchangeRequest: Boolean? = null,
    var redblackSourcePlatform: String? = null,
    var redblackKeyword: String? = null,
    var redblackListType: String? = null,
    var stationProductId: String? = null,
    var stationAppId: String? = null,
    var stationSourcePlatform: String? = null,
    var stationSourceSubChannel: String? = null,
    var stationApiVersion: String? = null,
    var channelMetaJson: String? = null,
    var diagnosticTypes: String? = null
)

