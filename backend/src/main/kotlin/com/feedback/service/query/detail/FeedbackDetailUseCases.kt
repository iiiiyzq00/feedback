package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.FeedbackDetailSnapshot
import com.feedback.application.port.feedback.detail.FeedbackFieldModificationFact
import com.feedback.application.port.feedback.detail.FeedbackTimelineSnapshot
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.WebPrincipal

/** 详情 HTTP 入口所依赖的业务用例。 */
fun interface FeedbackDetailQueryUseCase {
    fun detail(sourceId: Long): FeedbackDetailResponse
}

/** 字段修改历史 HTTP 入口所依赖的业务用例。 */
fun interface FeedbackModificationQueryUseCase {
    fun modifications(sourceId: Long): List<FeedbackFieldHistoryResponse>
}

/** 时间轴 HTTP 入口所依赖的业务用例。 */
fun interface FeedbackTimelineQueryUseCase {
    fun timeline(sourceId: Long): List<FeedbackTimelineEventResponse>
}

/** 来源系统外链 HTTP 入口所依赖的业务用例。 */
fun interface FeedbackExternalLinkQueryUseCase {
    fun externalLink(sourceId: Long): FeedbackExternalLinkResponse
}

/** 已完成失败关闭访问校验后固定的身份、范围和字段策略快照。 */
data class AuthorizedFeedbackRead(
    val scope: DataAccessScope,
    val principal: WebPrincipal,
    val fieldPolicy: DataFieldPolicy
)

/** 已完成失败关闭访问校验后固定的身份与数据范围快照。 */
data class AuthorizedFeedbackAccess(val scope: DataAccessScope, val principal: WebPrincipal)

data class AuthorizedDetailSnapshot(
    val authorization: AuthorizedFeedbackRead,
    val detail: FeedbackDetailSnapshot,
    val externalLinkAvailable: Boolean
)

data class AuthorizedModificationSnapshot(
    val authorization: AuthorizedFeedbackRead,
    val modifications: List<FeedbackFieldModificationFact>
)

data class AuthorizedTimelineSnapshot(
    val authorization: AuthorizedFeedbackRead,
    val timeline: FeedbackTimelineSnapshot
)

data class AuthorizedExternalLinkSnapshot(
    val principal: WebPrincipal,
    val sourceId: Long,
    val channel: String,
    val url: String,
    val host: String
)
