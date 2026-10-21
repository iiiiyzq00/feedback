package com.feedback.repository.problem.analysis

import com.feedback.application.port.problem.analysis.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.problem.analysis.ProblemClusterPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.springframework.stereotype.Repository

/**
 * 反馈问题分析的跨表快照编排边界。
 *
 * 原子查询和输入上界由事实 Repository 统一执行；本类只把固定 generation 下的批量事实组合为具名快照，
 * 不跨表拼 SQL，也不把 Mapper 或持久化条件暴露给应用用例。
 */
@Repository
class ProblemAnalysisReadRepository(
    private val facts: ProblemAnalysisFactsRepository,
    private val objectMapper: ObjectMapper
) : ProblemAnalysisReadPort, ClusterReadPort {
    /**
     * 按固定查询顺序读取总数、两个独立页和两页合并后的有界补充事实。
     * 两页可能含同一簇，因此先去重再批量读取，既保持行顺序也避免重复查询。
     */
    override fun read(request: ProblemAnalysisReadRequest): ProblemAnalysisSnapshot {
        validatePage(request.allClusters, "PROBLEM_ANALYSIS_PAGINATION_INVALID")
        validatePage(request.crossChannelClusters, "PROBLEM_ANALYSIS_PAGINATION_INVALID")
        val criteria = request.toCriteria()
        val totals = facts.totals(request.generationId, criteria)
        val allRows = facts.page(
            request.generationId,
            criteria,
            false,
            request.allClusters.size,
            request.allClusters.offset
        )
        val crossRows = facts.page(
            request.generationId,
            criteria,
            true,
            request.crossChannelClusters.size,
            request.crossChannelClusters.offset
        )
        val clusters = snapshots(request.generationId, criteria, allRows + crossRows)
        return ProblemAnalysisSnapshot(
            totals = ProblemAnalysisTotalsSnapshot(totals.allTotal, totals.crossChannelTotal),
            allClusters = allRows.map { clusters.getValue(it.clusterId) },
            crossChannelClusters = crossRows.map { clusters.getValue(it.clusterId) }
        )
    }

    override fun detail(request: ClusterDetailReadRequest): ClusterDetailSnapshot? {
        if (request.clusterId <= 0) invalid("FEEDBACK_ANALYSIS_ID_BATCH_INVALID")
        val criteria = request.toCriteria()
        val clusterId = request.clusterId
        val summary = facts.summary(request.generationId, criteria, clusterId) ?: return null
        val clusterFacts = facts.clusterFacts(listOf(clusterId))[clusterId] ?: return null
        val representativeId = facts.representatives(request.generationId, criteria, listOf(clusterId))[clusterId]
        val representative = representativeId?.let {
            facts.feedbackProjections(request.generationId, listOf(it))[it]
        }
        val identity = facts.identities(request.generationId, criteria, listOf(clusterId))[clusterId]
        return ClusterDetailSnapshot(
            id = clusterFacts.id,
            name = clusterFacts.name,
            canonicalSymptom = clusterFacts.canonicalSymptom,
            representativeDescription = representative?.description,
            representativeTitle = representative?.title,
            functionModule = clusterFacts.functionModule,
            issueCategory = clusterFacts.issueCategory,
            status = clusterFacts.status,
            lifecycleVersion = clusterFacts.lifecycleVersion,
            mergedIntoClusterId = clusterFacts.mergedIntoClusterId,
            splitFromClusterId = clusterFacts.splitFromClusterId,
            statusReason = clusterFacts.statusReason,
            recordCount = summary.recordCount,
            logicalChannelCount = summary.logicalChannelCount,
            lastOccurredAt = summary.lastOccurredAt,
            firstOccurredAt = summary.firstOccurredAt,
            userCount = identity?.userCount,
            deviceCount = identity?.deviceCount ?: 0,
            stationCount = summary.stationCount,
            jiraCount = summary.jiraCount,
            qualityCount = summary.qualityCount,
            redblackCount = summary.redblackCount
        )
    }

    override fun feedback(request: ClusterFeedbackReadRequest): ClusterFeedbackSnapshot {
        if (request.clusterId <= 0) invalid("FEEDBACK_ANALYSIS_ID_BATCH_INVALID")
        validatePage(request.page, "CLUSTER_FEEDBACK_PAGINATION_INVALID")
        val criteria = request.toCriteria()
        val total = facts.feedbackCount(request.generationId, criteria, request.clusterId)
        if (total == 0L) return ClusterFeedbackSnapshot(0, emptyList())
        val members = facts.feedbackPage(
            request.generationId,
            criteria,
            request.clusterId,
            request.page.size,
            request.page.offset
        )
        val projections = facts.feedbackProjections(request.generationId, members.map { it.sourceId })
        if (!projections.keys.containsAll(members.map { it.sourceId })) {
            unavailable("FEEDBACK_CLUSTER_FEEDBACK_PROJECTION_INCOMPLETE")
        }
        return ClusterFeedbackSnapshot(total, members.map { member ->
            val row = projections.getValue(member.sourceId)
            ClusterFeedbackItemSnapshot(
                sourceId = member.sourceId,
                sourceRecordId = row.sourceRecordId,
                title = row.title,
                description = row.description,
                sourceChannel = member.sourceChannel,
                reportedAt = member.reportedAt,
                controlledInfoTypes = controlledTypes(row.diagnosticTypes),
                attachmentCount = member.attachmentCount,
                screenshotCount = member.screenshotCount,
                logCount = member.logCount,
                videoCount = member.videoCount,
                decisionType = member.decisionType,
                matchSimilarity = member.matchSimilarity,
                decisionReason = member.decisionReason,
                aiModel = member.aiModel,
                aiConfidence = member.aiConfidence,
                aiAction = member.aiAction,
                aiExplanation = member.aiExplanation
            )
        })
    }


    private fun snapshots(
        generationId: Long,
        criteria: AnalysisCriteria,
        rows: List<ProblemClusterPo>
    ): Map<Long, ProblemClusterSnapshot> {
        val ids = rows.map { it.clusterId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val clusterFacts = facts.clusterFacts(ids)
        if (!clusterFacts.keys.containsAll(ids)) unavailable("FEEDBACK_CLUSTER_FACTS_INCOMPLETE")
        val identities = facts.identities(generationId, criteria, ids)
        val applications = facts.applications(generationId, criteria, ids)
        val regions = facts.regions(generationId, criteria, ids)
        val representativeIds = facts.representatives(generationId, criteria, ids)
        val representatives = facts.feedbackProjections(generationId, representativeIds.values.toList())
        return rows.distinctBy { it.clusterId }.associate { row ->
            val cluster = clusterFacts.getValue(row.clusterId)
            val representative = representativeIds[row.clusterId]?.let(representatives::get)
            row.clusterId to ProblemClusterSnapshot(
                clusterId = row.clusterId,
                canonicalSymptom = cluster.canonicalSymptom,
                representativeDescription = representative?.description,
                representativeTitle = representative?.title,
                functionModule = cluster.functionModule,
                issueCategory = cluster.issueCategory,
                recordCount = row.recordCount,
                userCount = identities[row.clusterId]?.userCount,
                deviceCount = identities[row.clusterId]?.deviceCount ?: 0,
                applications = applications[row.clusterId].orEmpty().map {
                    ProblemClusterDimensionSnapshot(it.dimensionValue, it.count)
                },
                firstOccurredAt = row.firstOccurredAt,
                lastOccurredAt = row.lastOccurredAt,
                attachmentCount = row.attachmentCount,
                screenshotCount = row.screenshotCount,
                logCount = row.logCount,
                regions = regions[row.clusterId].orEmpty().map {
                    ProblemClusterDimensionSnapshot(it.dimensionValue, it.count)
                }
            )
        }
    }

    private fun ProblemAnalysisReadRequest.toCriteria() = AnalysisCriteria(
        allowedChannels = scope.allowedChannels,
        scopeModules = scope.modules,
        scopeRegions = scope.regions,
        startTime = period.startTime,
        endTime = period.endTime
    )

    private fun ClusterDetailReadRequest.toCriteria() = criteria(scope, period)

    private fun ClusterFeedbackReadRequest.toCriteria() = criteria(scope, period)

    private fun criteria(
        scope: ProblemAnalysisReadScope,
        period: ProblemAnalysisReadPeriod
    ) = AnalysisCriteria(
        allowedChannels = scope.allowedChannels,
        scopeModules = scope.modules,
        scopeRegions = scope.regions,
        startTime = period.startTime,
        endTime = period.endTime
    )

    private fun controlledTypes(value: String?): List<String> = value?.let { json ->
        runCatching { objectMapper.readTree(json) }.getOrNull()?.takeIf { it.isArray }
            ?.mapNotNull { it.takeIf { node -> node.isTextual }?.textValue() }
            .orEmpty()
    }.orEmpty()

    /** 外部分页与标识在访问 Mapper 前拒绝，避免无效请求触发任何数据库读取。 */
    private fun validatePage(page: ProblemAnalysisReadPage, reasonCode: String) {
        if (page.page < 1 || page.size !in 1..100) invalid(reasonCode)
    }

    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

    private fun unavailable(reasonCode: String): Nothing = throw ServiceException(
        ErrorCode.FEEDBACK_READ_UNAVAILABLE,
        reasonCode
    )
}
