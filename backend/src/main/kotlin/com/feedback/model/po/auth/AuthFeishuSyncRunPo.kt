package com.feedback.model.po.auth

import java.time.LocalDateTime

/** 飞书全量授权同步运行记录；一条运行只汇总状态，不承载单用户授权事务。 */
data class AuthFeishuSyncRunPo(
    var id: Long? = null,
    val runStatus: String,
    val totalUsers: Int = 0,
    val changedUsers: Int = 0,
    val disabledUsers: Int = 0,
    val failedUsers: Int = 0,
    val errorCode: String? = null,
    val requestedBy: Long? = null,
    val startedAt: LocalDateTime? = null,
    val finishedAt: LocalDateTime? = null,
)
