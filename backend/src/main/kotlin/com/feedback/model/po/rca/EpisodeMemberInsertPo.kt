package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * `t_feedback_episode_member` 的插入行（append-only，永不更新）。
 *
 * `membership_status` 不在这个类型里：新追加的成员一律 `ACTIVE`（表默认值），
 * 历史 revision 由「同 source 的旧行被人工置为 SUPERSEDED」表达，
 * 而那是归因修订动作，不在接入路径上。
 *
 * [membershipRevision] 由会话化判定给出，**不是**持久化层自己排的序号：
 * 它是「这条信号属于第几次修订」这一判断的产物，由写入方重新推导会得到另一个值。
 */
data class EpisodeMemberInsertPo(
    var episodeId: Long = 0,
    var sourceId: Long = 0,
    var membershipRevision: Long = 0,
    var identityLevel: String = "",
    var joinReason: String = "",
    var eventTime: LocalDateTime? = null,
    var duplicateEndpoint: Boolean = false
)
