package com.feedback.model.po.rca

import java.math.BigDecimal

/**
 * 假设写入行，对应 `t_rca_hypothesis`。
 *
 * 幂等键是 `uk_rca_hypothesis_code (task_id, hypothesis_code, revision)`：
 * 修正过的假设以**新 revision 追加**，历史修订不被覆盖——这与「归因追加式」
 * 同一条设计原则，事后复核才能看到假设是怎么被证据改写的。
 *
 * `mergedIntoId` 刻意不在本行写入：合并目标的**行主键**要等被合并的那条假设先落库才有，
 * 因此合并链接由第二条语句（`linkHypothesisMerge`）按编码回填，
 * 而不是在这里塞一个同表子查询。
 */
data class RcaHypothesisInsertPo(
    val taskId: Long,
    val hypothesisCode: String,
    val statement: String,
    val mechanismNote: String?,
    val confidenceLevel: String,
    val confidenceScore: BigDecimal?,
    val hypothesisStatus: String,
    /** evidence_id 数组的 JSON 文本；无支持证据时给 `[]` 而不是 null。 */
    val supportingEvidenceJson: String,
    val refutingEvidenceJson: String,
    val unresolvedRefutationCount: Int,
    val revision: Long
)
