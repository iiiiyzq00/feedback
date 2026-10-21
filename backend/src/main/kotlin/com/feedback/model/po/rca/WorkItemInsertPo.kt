package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * `t_feedback_work_item` 的「建或取回」行。
 *
 * [id] 走 `useGeneratedKeys`：插入时是自增主键，命中 `uk_work_item_episode` 时由
 * `LAST_INSERT_ID(id)` 回填成**已存在那一行**的主键。两条路径因此拿到同一个标识，
 * 调用方不需要判断「该建还是该取」。
 */
data class WorkItemInsertPo(
    var publicId: String = "",
    var episodeId: Long = 0,
    var firstSeenAt: LocalDateTime? = null,
    var lastSeenAt: LocalDateTime? = null,
    var id: Long = 0
)
