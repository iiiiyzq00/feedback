package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * Episode 归因写入行，对应 `t_feedback_episode_attribution`。
 *
 * 幂等键是 `uk_episode_attribution_rev (episode_id, rca_task_id, attribution_revision)`：
 * 归因是追加式的，升级降级都新增一行，历史行保留。
 * `attribution_revision` 取写入时的 `RcaSharedState.stateVersion`，
 * 同一份状态重放会打到同一行，不会因为重放而多出一条归因。
 *
 * `incidentId` 在本行恒为 null：时序上要先有归因行、才谈得上关联 Incident
 * （`linkIncident` 拿的是本行的主键）。
 * `autoLinked` 与 `attributionLevel` 的关系由 `chk_episode_attribution_autolink` 约束，
 * 只有前两档可以为 1，写入方不得自行放宽。
 */
data class RcaAttributionInsertPo(
    /** 写入后由 `useGeneratedKeys` 回填；`linkIncident` 用的就是它。 */
    var id: Long = 0,
    val episodeId: Long,
    val rcaTaskId: Long,
    val rootCauseSignature: String,
    val attributionLevel: String,
    val attributionRevision: Long,
    /** evidence_id 数组的 JSON 文本。 */
    val basisEvidenceJson: String,
    val coverageNote: String?,
    val autoLinked: Boolean,
    val decidedAt: LocalDateTime,
    val decidedBy: Long?
)
