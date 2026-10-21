package com.feedback.model.po.rca

/**
 * 知识候选写入行，对应既有表 `t_ai_knowledge`。
 *
 * **`knowledgeStatus` 恒为 `DRAFT`**，这不是占位值而是本链路的要点：
 * `KnowledgeSearchMapper.search` 只召回 `knowledge_status='PUBLISHED'` 的行，
 * 所以 RCA 自己产出的结论永远不会在同一条链路里被当成历史证据召回。
 * 一旦这里写成 `PUBLISHED`，模型就能用自己上一轮的结论支持自己下一轮的假设——
 * 一个自我强化的闭环，而且从账本上看每条引用都「有出处」。
 *
 * 幂等靠 `source_rca_id + knowledge_source='AGENT_RCA'` 判存在：
 * 该表只有 `uk_public_id` 一个唯一键，而 `public_id` 每次生成都不同，
 * 不判存在的话持久化节点每重放一次就多一条候选。
 */
data class RcaKnowledgeCandidateInsertPo(
    /** 写入后由 `useGeneratedKeys` 回填。 */
    var id: Long = 0,
    val publicId: String,
    val knowledgeTitle: String,
    val knowledgeContent: String,
    val knowledgeTagsJson: String,
    val sourceRcaId: Long
)
