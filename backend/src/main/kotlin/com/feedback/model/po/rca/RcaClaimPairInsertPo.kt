package com.feedback.model.po.rca

/**
 * 引用支持校验结果写入行，对应 `t_rca_claim_evidence`。
 *
 * 这张表**没有唯一键**，`idx_rca_claim_task` 与 `idx_rca_claim_review` 都是普通索引，
 * 所以幂等不能靠 `ON DUPLICATE KEY`，只能靠「按 (task_id, claim_code, evidence_id)
 * 判存在再插入」。持久化节点会重放，没有这层判断就会每重放一次多出一批校验行，
 * 而「引用支持正确率」正是按这张表复算的——重复行会直接把这个比率稀释掉。
 *
 * `hypothesisId` 由 `(task_id, hypothesis_code)` 子查询解析，可以为 null：
 * claim 不一定挂在某个假设上，null 表示这条 claim 是任务级的。
 */
data class RcaClaimPairInsertPo(
    val taskId: Long,
    /** 由 `claim_code` 的 `#` 前缀解析而来；为空表示不关联具体假设。 */
    val hypothesisCode: String?,
    val claimCode: String,
    val claimType: String,
    val claimText: String,
    val evidenceId: String,
    /** 判定时快照的证据覆盖类型，便于事后复核是否用错了粒度。 */
    val evidenceCoverageType: String,
    /** 按 claim 类型推导出的最低覆盖粒度要求。 */
    val requiredCoverageType: String,
    val supportVerdict: String,
    val verdictReason: String?
)
