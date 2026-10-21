package com.feedback.service.query.analysis

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.common.exception.ClientException
import com.feedback.common.FeedbackChannel
import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPage
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPeriod
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadRequest
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadScope
import com.feedback.application.port.problem.analysis.ProblemClusterSnapshot
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.PrincipalAccess
import com.feedback.service.cluster.ProductPhenomenonFormatter
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 反馈问题深度分析查询用例。
 *
 * 本类只负责权限、读开关、generation 固定、查询校验和公开响应映射。组合数据库读取由
 * [ProblemAnalysisReadPort] 返回不可变快照；不负责簇详情、簇内反馈或任何远程分析。
 */
@Service
class ProblemAnalysisApplicationService(
    private val properties: ProblemAnalysisReadProperties,
    private val generations: ActiveGenerationProvider,
    private val reads: ProblemAnalysisReadPort,
    private val permissions: DataPermissionService,
    private val principal: CurrentWebPrincipalProvider
) : ProblemAnalysisQueryUseCase {
    private val feedbackChannels = FeedbackChannel.feedbackWireValues

    /** 两个分页区在同一可重复读快照内组装；空时间范围继续表示全部历史。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun analyze(query: ProblemAnalysisQuery): ProblemAnalysisResponse {
        PrincipalAccess.requireWorkspace(principal.require())
        validate(query)
        val generation = generation()
        val snapshot = reads.read(
            ProblemAnalysisReadRequest(
                generationId = generation.id,
                scope = scope(),
                period = ProblemAnalysisReadPeriod(query.period.startTime, query.period.endTime),
                allClusters = ProblemAnalysisReadPage(query.allClusters.page, query.allClusters.size),
                crossChannelClusters = ProblemAnalysisReadPage(
                    query.crossChannelClusters.page,
                    query.crossChannelClusters.size
                )
            )
        )
        return ProblemAnalysisResponse(
            allClusters = page(
                snapshot.allClusters,
                snapshot.totals.allClusters,
                query.allClusters
            ),
            crossChannelClusters = page(
                snapshot.crossChannelClusters,
                snapshot.totals.crossChannelClusters,
                query.crossChannelClusters
            ),
            startTime = query.period.startTime,
            endTime = query.period.endTime,
            generatedAt = LocalDateTime.now()
        )
    }

    private fun page(
        rows: List<ProblemClusterSnapshot>,
        total: Long,
        requested: ProblemAnalysisPage
    ) = ProblemClusterPageResponse(rows.map(::item), total, requested.page, requested.size)

    private fun item(row: ProblemClusterSnapshot) = ProblemClusterItemResponse(
        clusterId = row.clusterId,
        phenomenon = ProductPhenomenonFormatter.choose(
            row.canonicalSymptom,
            row.representativeDescription?.takeIf(String::isNotBlank)
                ?: row.representativeTitle?.takeIf(String::isNotBlank),
            row.functionModule,
            row.issueCategory
        ),
        recordCount = row.recordCount,
        userCount = row.userCount,
        deviceCount = row.deviceCount,
        affectedApplications = row.applications.take(5).map {
            ProblemClusterDimensionResponse(application = it.value, count = it.count)
        },
        firstOccurredAt = row.firstOccurredAt,
        lastOccurredAt = row.lastOccurredAt,
        attachmentCount = row.attachmentCount,
        screenshotCount = row.screenshotCount,
        logCount = row.logCount,
        regions = row.regions.take(5).map {
            ProblemClusterDimensionResponse(region = it.value, count = it.count)
        }
    )

    private fun scope(): ProblemAnalysisReadScope {
        val scope = permissions.scope()
        return ProblemAnalysisReadScope(
            allowedChannels = scope.channels.map(String::uppercase).intersect(feedbackChannels).sorted()
                .ifEmpty { listOf("__NONE__") },
            modules = scope.modules?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") },
            regions = scope.regions?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") }
        )
    }

    private fun generation() = if (!properties.readEnabled) {
        throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_PROBLEM_ANALYSIS_READ_DISABLED")
    } else {
        generations.require(ProjectionCapability.PROBLEM_ANALYSIS)
    }

    private fun validate(query: ProblemAnalysisQuery) {
        val period = query.period
        if ((period.startTime == null) != (period.endTime == null)) {
            throw ClientException(ErrorCode.BAD_REQUEST, "PROBLEM_ANALYSIS_PERIOD_INCOMPLETE")
        }
        if (period.startTime != null && period.endTime != null) {
            if (period.startTime.isAfter(period.endTime)) {
                throw ClientException(ErrorCode.BAD_REQUEST, "PROBLEM_ANALYSIS_PERIOD_INVALID")
            }
        }
        listOf(query.allClusters, query.crossChannelClusters).forEach {
            if (it.page < 1 || it.size !in 1..100) {
                throw ClientException(ErrorCode.BAD_REQUEST, "PROBLEM_ANALYSIS_PAGINATION_INVALID")
            }
        }
    }
}
