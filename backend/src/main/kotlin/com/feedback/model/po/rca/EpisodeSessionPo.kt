package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * `t_feedback_episode` 的行视图，供会话化判定读取。
 *
 * 只含判定与计数需要的列，不含 `identity_fingerprint`：分区查询是**按指纹查**，
 * 查回来的行再带上指纹只会让人以为「这个指纹是读出来的」，而它其实来自查询条件。
 *
 * 时间列用 [LocalDateTime] 而不是字符串：`DATETIME(3)` 的先后比较是会话化的核心判断，
 * 把它降级成字符串比较会在跨毫秒格式差异时静默给出错误结果。
 */
data class EpisodeSessionPo(
    var id: Long = 0,
    var generation: Int = 0,
    var identityLevel: String = "",
    var errorSignature: String? = null,
    var sessionState: String = "",
    var sessionOpenedAt: LocalDateTime? = null,
    var sessionLastEventAt: LocalDateTime? = null,
    var memberRevision: Long = 0,
    var repeatCount: Int = 0,
    var distinctUserCount: Int = 0,
    var distinctDeviceCount: Int = 0
)
