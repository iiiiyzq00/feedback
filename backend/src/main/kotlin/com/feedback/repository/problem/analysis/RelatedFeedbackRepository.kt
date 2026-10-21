package com.feedback.repository.problem.analysis

import com.feedback.application.port.problem.analysis.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.problem.analysis.ClusterFeedbackProjectionMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.springframework.stereotype.Repository

/** 同簇反馈的独立有界读模型，避免继续扩张问题深度分析 Repository。 */
@Repository
class RelatedFeedbackRepository(
    private val members: ProblemAnalysisMemberMapper,
    private val projections: ClusterFeedbackProjectionMapper
) : RelatedFeedbackReadPort {
    override fun read(request: RelatedFeedbackReadRequest): RelatedFeedbackSnapshot {
        if (request.page < 1 || request.size !in 1..50) {
            throw ClientException(ErrorCode.BAD_REQUEST, "RELATED_FEEDBACK_PAGINATION_INVALID")
        }
        val criteria = AnalysisCriteria(
            request.scope.allowedChannels,
            request.scope.modules,
            request.scope.regions,
            null,
            null
        )
        val own = members.findMemberBySourceId(request.generationId, criteria, request.sourceId)
            ?: return RelatedFeedbackSnapshot(emptyList(), 0)
        val total = members.countRelatedFeedback(request.generationId, criteria, own.clusterId, request.sourceId)
        val rows = members.findRelatedFeedbackPage(
            request.generationId,
            criteria,
            own.clusterId,
            request.sourceId,
            request.size,
            (request.page - 1L) * request.size
        )
        val sourceIds = rows.map { it.sourceId }.distinct()
        val content = if (sourceIds.isEmpty()) emptyMap() else {
            if (sourceIds.size > 50) unavailable("RELATED_FEEDBACK_ID_BATCH_INVALID")
            projections.findByIds(request.generationId, sourceIds).associateBy { it.sourceId }
        }
        if (!content.keys.containsAll(sourceIds)) unavailable("FEEDBACK_RELATED_FEEDBACK_PROJECTION_INCOMPLETE")
        return RelatedFeedbackSnapshot(
            rows.map { row ->
                val projection = content.getValue(row.sourceId)
                RelatedFeedbackItemSnapshot(
                    row.sourceId,
                    row.clusterId,
                    row.sourceChannel,
                    projection.sourceRecordId,
                    projection.title,
                    projection.description,
                    row.reportedAt,
                    row.matchSimilarity,
                    row.decisionReason
                )
            },
            total
        )
    }

    private fun unavailable(reasonCode: String): Nothing = throw ServiceException(
        ErrorCode.FEEDBACK_READ_UNAVAILABLE,
        reasonCode
    )
}
