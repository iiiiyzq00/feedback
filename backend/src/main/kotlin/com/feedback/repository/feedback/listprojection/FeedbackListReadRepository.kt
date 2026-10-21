package com.feedback.repository.feedback.listprojection

import com.feedback.application.port.feedback.list.*
import com.feedback.mapper.feedback.listprojection.FeedbackListOwnerFactsMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionPo
import com.feedback.mapper.feedback.listprojection.FeedbackListGenerationReadMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionReadMapper
import com.feedback.model.query.feedback.list.FeedbackListReadCriteria
import com.feedback.model.po.feedback.listprojection.FeedbackListStatsPo
import org.springframework.stereotype.Repository

/** 反馈列表公开读取边界；只协调 generation、投影和负责人三张单表 Mapper。 */
@Repository
class FeedbackListReadRepository(
    private val projectionMapper: FeedbackListProjectionReadMapper,
    private val ownerMapper: FeedbackListOwnerFactsMapper
) : FeedbackListReadPort {
    override fun page(
        generationId: Long,
        criteria: FeedbackListCriteria,
        limit: Int,
        offset: Long
    ): List<FeedbackListRecord> {
        if (limit !in 1..100 || offset < 0) invalidProjectionInput("FEEDBACK_FEEDBACK_LIST_PAGE_INVALID")
        return projectionMapper.findPage(generationId, criteria.toPersistence(), limit, offset).map { it.toRecord() }
    }

    override fun exportRows(
        generationId: Long,
        criteria: FeedbackListCriteria,
        limit: Int
    ): List<FeedbackListRecord> {
        if (limit != 5001) invalidProjectionInput("FEEDBACK_FEEDBACK_EXPORT_SENTINEL_INVALID")
        return projectionMapper.findForExport(generationId, criteria.toPersistence(), limit).map { it.toRecord() }
    }

    override fun rowsByIds(
        generationId: Long,
        criteria: FeedbackListCriteria,
        sourceIds: List<Long>
    ): List<FeedbackListRecord> {
        val normalized = sourceIds.distinct()
        if (normalized.isEmpty() || normalized.size > 200 || normalized.any { it <= 0 }) {
            invalidProjectionInput("FEEDBACK_FEEDBACK_EXPORT_ID_BATCH_INVALID")
        }
        return projectionMapper.findByIds(generationId, criteria.toPersistence(), normalized).map { it.toRecord() }
    }

    override fun stats(generationId: Long, criteria: FeedbackListCriteria): FeedbackListStats =
        projectionMapper.stats(generationId, criteria.toPersistence()).toStats()

    override fun owners(ownerIds: List<Long>): Map<Long, String> {
        val normalized = ownerIds.distinct()
        if (normalized.size > 100 || normalized.any { it <= 0 }) invalidProjectionInput("FEEDBACK_FEEDBACK_OWNER_BATCH_INVALID")
        if (normalized.isEmpty()) return emptyMap()
        return ownerMapper.findByIds(normalized).associate { row ->
            row.id to (row.displayName?.takeIf(String::isNotBlank) ?: row.username)
        }
    }

    private fun FeedbackListCriteria.toPersistence() = FeedbackListReadCriteria(
        allowedChannels = allowedChannels, scopeModules = scopeModules, scopeRegions = scopeRegions,
        channels = channels, keywordPattern = keyword?.likePattern(), severities = severities, statuses = statuses,
        modules = modules, problemTypes = problemTypes, reproducibilities = reproducibilities,
        aiReviewStatus = aiReviewStatus, province = province, city = city, ownerId = ownerId,
        unassigned = unassigned, handoffPending = handoffPending, startTime = startTime, endTime = endTime,
        appId = appId, packageName = packageName, deviceModelPattern = deviceModel?.likePattern(),
        osVersionPattern = osVersion?.likePattern(), networkType = networkType, appVersion = appVersion,
        projectKey = projectKey, issueKey = issueKey, issueType = issueType, priority = priority,
        assignee = assignee, resolution = resolution, repairRegionPattern = repairRegion?.likePattern(),
        applicationType = applicationType, exchangeRequest = exchangeRequest, importBatchCode = importBatchCode,
        sourcePlatform = sourcePlatform, emotionType = emotionType, channelKeyword = channelKeyword,
        listType = listType, productId = productId, sourceSubChannel = sourceSubChannel, apiVersion = apiVersion,
        versionCompleteness = versionCompleteness, sourceResolutionState = sourceResolutionState,
        sortBy = sortBy, sortDirection = sortDirection
    )

    private fun String.likePattern(): String = "%${replace("=", "==").replace("%", "=%").replace("_", "=_")}%"

    private fun FeedbackListProjectionPo.toRecord() = FeedbackListRecord(
        sourceId = sourceId, sourceChannel = sourceChannel, sourceRecordId = sourceRecordId,
        appVersion = appVersion, packageName = packageName, provinceCode = provinceCode, cityCode = cityCode,
        importBatchId = importBatchId, title = title, description = description, functionModule = functionModule,
        problemType = problemType, issueCategory = issueCategory, severityLevel = severityLevel, aiSeverity = aiSeverity,
        aiReviewStatus = aiReviewStatus, userSentiment = userSentiment, processingStatus = processingStatus,
        distributionStatus = distributionStatus, ownerUserId = ownerUserId, reportedAt = reportedAt,
        sourceUpdateTime = sourceUpdateTime, platformUpdateTime = platformUpdateTime, ingestTime = ingestTime,
        attachmentCount = attachmentCount, screenshotCount = screenshotCount, logCount = logCount, videoCount = videoCount,
        channelAppId = channelAppId, channelPackageName = channelPackageName, stationAppId = stationAppId,
        channelMetaJson = channelMetaJson, diagnosticTypes = diagnosticTypes
    )

    private fun FeedbackListStatsPo.toStats() = FeedbackListStats(
        total = total,
        latestTime = latestTime,
        latestUpdateTime = latestUpdateTime,
        latestIngestTime = latestIngestTime,
        channelCount = channelCount,
        unassignedCount = unassignedCount,
        activeCount = activeCount,
        resolvedCount = resolvedCount,
        withEvidenceCount = withEvidenceCount,
        positiveCount = positiveCount,
        negativeCount = negativeCount,
        newRecords5m = newRecords5m
    )
}
