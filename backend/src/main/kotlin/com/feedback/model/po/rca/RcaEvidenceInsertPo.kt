package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * 证据账本写入行，对应 `t_rca_evidence`。
 *
 * 幂等键是 `uk_rca_evidence_id (task_id, evidence_id)`：evidence ID 由工具与参数摘要推导，
 * 天然稳定，因此持久化节点重放时打到同一行上而不是写出两条。
 *
 * `content_digest` 落库前必须已归一成**小写 64 位十六进制**——
 * `chk_rca_evidence_digest` 是对列值的正则约束，格式不对会整条语句失败。
 *
 * `coverage_type` 为 `COHORT` 时，`denominator` 与 `control_group` 都必须有值
 * （`chk_rca_evidence_cohort_complete`）：没有分母的范围性证据不允许入库，
 * 否则「故障集中在新版本」这类结论就能被一条没有分母的证据支撑。
 */
data class RcaEvidenceInsertPo(
    val taskId: Long,
    val evidenceId: String,
    val evidenceType: String,
    val sourceTool: String,
    val sourceRef: String,
    val coverageType: String,
    val coverageScope: String,
    val numerator: Long?,
    val denominator: Long?,
    val controlGroup: String?,
    val observedFrom: LocalDateTime?,
    val observedTo: LocalDateTime?,
    val timeWatermark: LocalDateTime?,
    val permissionScope: String,
    val contentDigest: String,
    val summary: String,
    val rawReference: String?,
    val revision: Long
)
