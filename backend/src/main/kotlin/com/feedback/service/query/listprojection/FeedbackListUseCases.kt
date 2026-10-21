package com.feedback.service.query.listprojection

import com.feedback.service.query.FeedbackListQuery

/** HTTP 列表适配器可调用的应用边界；不暴露 Repository 或持久化条件。 */
interface FeedbackListQueryUseCase {
    fun list(query: FeedbackListQuery): FeedbackListResponse
}

/** HTTP 导出适配器可调用的应用边界；负责查询导出和显式选择导出两种既有模式。 */
interface FeedbackExportUseCase {
    fun exportQuery(query: FeedbackListQuery, auditContext: FeedbackExportAuditContext): String
    fun exportSelection(ids: List<Long>, auditContext: FeedbackExportAuditContext): String
}
