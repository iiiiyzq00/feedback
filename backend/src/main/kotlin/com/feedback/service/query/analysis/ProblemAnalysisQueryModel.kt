package com.feedback.service.query.analysis

import java.time.LocalDateTime

/** 问题分析公开应用用例；HTTP 适配器只提交一个具名查询，避免分页位置参数错配。 */
interface ProblemAnalysisQueryUseCase {
    fun analyze(query: ProblemAnalysisQuery): ProblemAnalysisResponse
}

/** 问题分析查询；两个分页值属于不同结果区，不能合并或互相覆盖。 */
data class ProblemAnalysisQuery(
    val period: ProblemAnalysisPeriod,
    val allClusters: ProblemAnalysisPage,
    val crossChannelClusters: ProblemAnalysisPage
)

/** 可选的闭区间来源语义时间；起止必须同时存在或同时为空。 */
data class ProblemAnalysisPeriod(val startTime: LocalDateTime?, val endTime: LocalDateTime?)

/** 从 1 开始的有界分页请求。 */
data class ProblemAnalysisPage(val page: Int, val size: Int) {
    val offset: Long get() = (page - 1L) * size
}
