package com.feedback.model.ai

import java.time.LocalDateTime

/** AI 任务领域状态；租约、重试与完成字段只允许由任务编排按状态机推进。 */
data class AiTask(
    val id: Long = 0,
    val recordType: String = "INVOCATION",
    val skillCode: String? = null,
    val promptVersion: String? = null,
    val sourceId: Long? = null,
    val taskKey: String? = null,
    var taskStatus: String? = null,
    var leaseHolder: String? = null,
    var leaseExpiresAt: LocalDateTime? = null,
    /** 本次租约的 fencing token；必须与 leaseHolder 一起随写回提交，字段位置必须与 AiTaskPo 保持一致。 */
    var leaseGeneration: Long = 0,
    var nextRetryAt: LocalDateTime? = null,
    var retryCount: Int = 0,
    var inputSnapshot: String? = null,
    var outputSnapshot: String? = null,
    var snapshotRetentionUntil: LocalDateTime? = null,
    var errorDetail: String? = null,
    var completedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
