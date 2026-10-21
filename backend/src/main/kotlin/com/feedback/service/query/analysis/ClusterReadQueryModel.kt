package com.feedback.service.query.analysis

/** 问题簇详情与簇内反馈公开用例；隔离 HTTP 与持久化实现。 */
interface ClusterReadUseCase {
    fun detail(query: ClusterDetailQuery): ClusterDetailResponse
    fun feedback(query: ClusterFeedbackQuery): ClusterFeedbackPageResponse
}

data class ClusterDetailQuery(val clusterId: Long, val period: ProblemAnalysisPeriod)

data class ClusterFeedbackQuery(
    val clusterId: Long,
    val page: ProblemAnalysisPage,
    val period: ProblemAnalysisPeriod
)
