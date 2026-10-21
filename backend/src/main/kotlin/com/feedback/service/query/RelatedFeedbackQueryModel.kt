package com.feedback.service.query

/** 同簇反馈公开应用用例；HTTP 层只传递经过参数校验的具名值。 */
interface RelatedFeedbackQueryUseCase {
    fun related(sourceId: Long, page: Int, size: Int): RelatedFeedbackPageResponse
}
