package com.feedback.model.po.rca

/**
 * 强制证据计划完成情况写入行，对应 `t_rca_mandatory_evidence`。
 *
 * 幂等键是 `uk_rca_mandatory (task_id, requirement_code)`：一个任务里同一项门禁只有一行，
 * 重放时更新它的完成状态而不是再插一行。
 *
 * `chk_rca_mandatory_terminal` 要求：`completion_status` 是 `PENDING` 或 `WAIVED`，
 * 或者给出了 `satisfied_by_evidence_id`，或者给出了 `failure_semantic`。
 * 也就是说「已完成但没说被哪条证据满足」「失败但没留失败语义」这两种半成品状态
 * 在库层面就不允许存在——失败语义必须保留，不能改写成「查过没问题」。
 */
data class RcaMandatoryEvidenceInsertPo(
    val taskId: Long,
    val requirementCode: String,
    val requirementType: String,
    val triggerRule: String,
    val requiredReason: String,
    val completionStatus: String,
    val satisfiedByEvidenceId: String?,
    val failureSemantic: String?,
    val waiverReason: String?
)
