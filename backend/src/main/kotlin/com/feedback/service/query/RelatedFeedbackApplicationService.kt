package com.feedback.service.query

import com.feedback.common.FeedbackChannel
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.config.FeedbackListReadProperties
import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.security.FeedbackAccessGuard
import com.feedback.application.port.problem.analysis.RelatedFeedbackReadPort
import com.feedback.application.port.problem.analysis.RelatedFeedbackReadRequest
import com.feedback.application.port.problem.analysis.RelatedFeedbackScope
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 反馈同簇反馈读取用例。
 *
 * 本用例先完成当前反馈的公共访问校验，并固定该次校验得到的数据范围；随后在 `REPEATABLE_READ`
 * 只读事务中要求列表和问题分析两个开关同时启用，固定一个具备问题分析能力的完整 ACTIVE generation。
 * 当前反馈不是该范围内的可见活动簇成员时返回空页；存在成员时，相关成员排除自身并先分页，
 * 随后批量加载同 generation 的安全正文投影。任一正文缺失均整体失败，不返回成员事实与安全正文
 * 不一致的部分结果。
 */
@Service
class RelatedFeedbackApplicationService(
    private val listProperties: FeedbackListReadProperties,
    private val analysisProperties: ProblemAnalysisReadProperties,
    private val generations: ActiveGenerationProvider,
    private val reads: RelatedFeedbackReadPort,
    private val accessGuard: FeedbackAccessGuard
) : RelatedFeedbackQueryUseCase {
    private val channels = FeedbackChannel.feedbackWireValues

    /** 返回排除当前反馈自身的稳定分页；页大小限制为 1 至 50。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun related(sourceId: Long, page: Int, size: Int): RelatedFeedbackPageResponse {
        val access = accessGuard.require(sourceId)
        if (sourceId <= 0) throw ClientException(ErrorCode.BAD_REQUEST, "FEEDBACK_ID_INVALID")
        if (page < 1) throw ClientException(ErrorCode.BAD_REQUEST, "RELATED_FEEDBACK_PAGE_INVALID")
        if (size !in 1..50) throw ClientException(ErrorCode.BAD_REQUEST, "RELATED_FEEDBACK_SIZE_INVALID")
        if (!listProperties.readEnabled || !analysisProperties.readEnabled) unavailable("READ_DISABLED")
        val generation = generations.require(ProjectionCapability.PROBLEM_ANALYSIS)
        val accessScope = access.scope
        val scope = RelatedFeedbackScope(
            accessScope.channels.map(String::uppercase).intersect(channels).sorted().ifEmpty { listOf("__NONE__") },
            accessScope.modules?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") },
            accessScope.regions?.map(String::uppercase)?.sorted()?.ifEmpty { listOf("__NONE__") }
        )
        val snapshot = reads.read(
            RelatedFeedbackReadRequest(generation.id, scope, sourceId, page, size)
        )
        return RelatedFeedbackPageResponse(snapshot.items.map { row ->
            RelatedFeedbackItemResponse(
                row.sourceId, row.sourceChannel.lowercase(), row.sourceRecordId,
                row.description?.takeIf(String::isNotBlank) ?: row.title?.takeIf(String::isNotBlank) ?: "(无内容)",
                row.reportedAt, row.matchSimilarity, row.decisionReason, clusterId = row.clusterId
            )
        }, snapshot.total, page, size)
    }

    private fun unavailable(suffix: String): Nothing = throw ServiceException(
        ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_RELATED_FEEDBACK_$suffix"
    )
}
