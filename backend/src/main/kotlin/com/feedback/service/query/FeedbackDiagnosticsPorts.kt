package com.feedback.service.query

/** 诊断查看公开应用用例；GET 与兼容 POST 共享同一固定动作语义。 */
interface FeedbackDiagnosticsQueryUseCase {
    fun viewDirect(sourceId: Long, ipAddress: String?): FeedbackDiagnosticsResponse
}
