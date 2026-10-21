package com.feedback.service.query.analysis

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.common.FeedbackChannel
import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.application.port.problem.analysis.ClusterDetailReadRequest
import com.feedback.application.port.problem.analysis.ClusterFeedbackReadRequest
import com.feedback.application.port.problem.analysis.ClusterReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPage
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPeriod
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadScope
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.PrincipalAccess
import com.feedback.service.cluster.ProductPhenomenonFormatter
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

/**
 * 反馈问题簇详情与簇内反馈读取用例。
 *
 * 仅负责权限、generation、校验和公开响应映射；数据库事实与受控 JSON 解码由 [ClusterReadPort]
 * 封装，不承担问题分析双分页，也不调用远程分析、文件或对象存储。
 */
@Service
class ClusterReadApplicationService(
    private val properties: ProblemAnalysisReadProperties,
    private val generations: ActiveGenerationProvider,
    private val reads: ClusterReadPort,
    private val permissions: DataPermissionService,
    private val principal: CurrentWebPrincipalProvider
) : ClusterReadUseCase {
    private val feedbackChannels = FeedbackChannel.feedbackWireValues

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun detail(query: ClusterDetailQuery): ClusterDetailResponse {
        PrincipalAccess.requireWorkspaceOrAiReports(principal.require())
        validate(query.clusterId, query.period)
        val row = reads.detail(
            ClusterDetailReadRequest(
                generation().id,
                scope(),
                query.clusterId,
                ProblemAnalysisReadPeriod(query.period.startTime, query.period.endTime)
            )
        ) ?: notFound()
        val first = row.firstOccurredAt
        val latest = row.lastOccurredAt
        return ClusterDetailResponse(
            id = row.id,
            name = row.name,
            canonicalSymptom = ProductPhenomenonFormatter.choose(
                row.canonicalSymptom,
                row.representativeDescription?.takeIf(String::isNotBlank)
                    ?: row.representativeTitle?.takeIf(String::isNotBlank),
                row.functionModule,
                row.issueCategory
            ),
            functionModule = row.functionModule,
            issueCategory = row.issueCategory,
            status = row.status,
            lifecycleVersion = row.lifecycleVersion,
            mergedIntoClusterId = row.mergedIntoClusterId,
            splitFromClusterId = row.splitFromClusterId,
            statusReason = row.statusReason,
            totalFeedback = row.recordCount,
            channelCount = row.logicalChannelCount,
            latestTime = latest,
            firstTime = first,
            activeDays = if (first == null || latest == null) 0
            else ChronoUnit.DAYS.between(first.toLocalDate(), latest.toLocalDate()) + 1,
            uniqueUsers = row.userCount,
            uniqueDevices = row.deviceCount,
            stationCount = row.stationCount,
            jiraCount = row.jiraCount,
            qualityCount = row.qualityCount,
            redblackCount = row.redblackCount
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun feedback(query: ClusterFeedbackQuery): ClusterFeedbackPageResponse {
        PrincipalAccess.requireWorkspaceOrAiReports(principal.require())
        validate(query.clusterId, query.period)
        if (query.page.page < 1 || query.page.size !in 1..100) {
            throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_FEEDBACK_PAGINATION_INVALID")
        }
        val snapshot = reads.feedback(
            ClusterFeedbackReadRequest(
                generation().id,
                scope(),
                query.clusterId,
                ProblemAnalysisReadPage(query.page.page, query.page.size),
                ProblemAnalysisReadPeriod(query.period.startTime, query.period.endTime)
            )
        )
        if (snapshot.total == 0L) notFound()
        return ClusterFeedbackPageResponse(
            items = snapshot.items.map { row ->
                ClusterFeedbackItemResponse(
                    id = row.sourceId,
                    sourceRecordId = row.sourceRecordId,
                    issueContent = row.description?.takeIf(String::isNotBlank)
                        ?: row.title?.takeIf(String::isNotBlank) ?: "(无内容)",
                    sourceChannel = row.sourceChannel.lowercase(),
                    reportedAt = row.reportedAt,
                    controlledInfoTypes = row.controlledInfoTypes.joinToString(",").takeIf(String::isNotBlank),
                    attachmentCount = row.attachmentCount,
                    screenshotCount = row.screenshotCount,
                    logCount = row.logCount,
                    videoCount = row.videoCount,
                    decisionType = row.decisionType,
                    similarity = row.matchSimilarity,
                    decisionReason = row.decisionReason,
                    aiModel = row.aiModel,
                    aiConfidence = row.aiConfidence,
                    aiAction = row.aiAction,
                    aiExplanation = row.aiExplanation
                )
            },
            total = snapshot.total,
            page = query.page.page,
            size = query.page.size
        )
    }

    private fun scope(): ProblemAnalysisReadScope {
        val scope = permissions.scope()
        return ProblemAnalysisReadScope(
            scope.channels.map(String::uppercase).intersect(feedbackChannels).sorted()
                .ifEmpty { listOf("__NONE__") },
            scope.modules?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") },
            scope.regions?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") }
        )
    }

    private fun generation() = if (!properties.readEnabled) {
        throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_PROBLEM_ANALYSIS_READ_DISABLED")
    } else {
        generations.require(ProjectionCapability.PROBLEM_ANALYSIS)
    }

    private fun validate(clusterId: Long, period: ProblemAnalysisPeriod) {
        if (clusterId <= 0) throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_ID_INVALID")
        if ((period.startTime == null) != (period.endTime == null)) {
            throw ClientException(ErrorCode.BAD_REQUEST, "PROBLEM_ANALYSIS_PERIOD_INCOMPLETE")
        }
        if (period.startTime != null && period.endTime != null) {
            if (period.startTime.isAfter(period.endTime)) {
                throw ClientException(ErrorCode.BAD_REQUEST, "PROBLEM_ANALYSIS_PERIOD_INVALID")
            }
        }
    }

    private fun notFound(): Nothing = throw ClientException(ErrorCode.NOT_FOUND, "CLUSTER_NOT_FOUND")
}
