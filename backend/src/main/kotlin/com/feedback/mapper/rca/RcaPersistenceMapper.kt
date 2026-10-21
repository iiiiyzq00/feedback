package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaAttributionInsertPo
import com.feedback.model.po.rca.RcaClaimPairInsertPo
import com.feedback.model.po.rca.RcaEvidenceInsertPo
import com.feedback.model.po.rca.RcaHypothesisInsertPo
import com.feedback.model.po.rca.RcaIncidentInsertPo
import com.feedback.model.po.rca.RcaKnowledgeCandidateInsertPo
import com.feedback.model.po.rca.RcaMandatoryEvidenceInsertPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * RCA 持久化节点的语句集合：证据账本、假设、强制证据、归因、引用校验、Incident、知识候选。
 *
 * 所有写方法都必须幂等，理由是 `RcaPersistNode` 会重放（「归因已写库、任务行尚未置终态」
 * 窗口崩溃后恢复会从头再跑一遍）。幂等的实现分三种，选哪种取决于表上有什么：
 *
 * - 有确定性唯一键的（证据、假设、强制证据、归因）→ `ON DUPLICATE KEY UPDATE`；
 * - 没有唯一键的（引用校验）→ `INSERT ... SELECT ... WHERE NOT EXISTS`；
 * - 唯一键是随机值的（知识候选）→ 先按业务键查回已有主键，命中就直接返回。
 *
 * 参数一律用具名 `@Param`，单 PO 入参除外——那种情况下 XML 里必须用**裸属性名**
 * （`#{taskId}` 而不是 `#{row.taskId}`），这是本仓库既有的写法，
 * 也是 `EpisodeSessionMapper.upsertWorkItem` 曾经踩过的坑。
 */
@Mapper
interface RcaPersistenceMapper {

    /** 写一条证据；重复投递更新同一行。 */
    fun upsertEvidence(row: RcaEvidenceInsertPo): Int

    /** 写一条假设修订；`(task_id, hypothesis_code, revision)` 冲突即更新。 */
    fun upsertHypothesis(row: RcaHypothesisInsertPo): Int

    /**
     * 回填假设合并链接。
     *
     * 单独一条语句是因为合并目标的**行主键**要等被合并方先落库才存在，
     * 不能在插入那一句里用同表子查询拿到。
     */
    fun linkHypothesisMerge(
        @Param("taskId") taskId: Long,
        @Param("hypothesisCode") hypothesisCode: String,
        @Param("mergedIntoCode") mergedIntoCode: String
    ): Int

    /** 写一项强制证据的完成情况。 */
    fun upsertMandatoryEvidence(row: RcaMandatoryEvidenceInsertPo): Int

    /** 写一行归因；返回受影响行数，主键由入参 PO 的 `id` 回填。 */
    fun upsertAttribution(row: RcaAttributionInsertPo): Int

    /**
     * 读任务当前的 `state_version`，任务不存在时返回 null。
     *
     * 存在的唯一理由是领域事件的 `aggregate_version` 必须与任务行一致：归因写入这条路径上
     * 没有租约令牌可用（`RcaPersistencePort` 的入参里只有 `taskId`），而事件表那一列是
     * NOT NULL。用主键读一次是这里最便宜的做法；**不缓存**——缓存会让「读到的是哪一版」
     * 变成一个需要跨调用追踪的问题，而这条路径上一个任务只写一次归因。
     */
    fun findTaskStateVersion(@Param("taskId") taskId: Long): Long?

    /** 写一对引用支持校验结果；已存在则不动。 */
    fun insertClaimPairIfAbsent(row: RcaClaimPairInsertPo): Int

    /** 按标题找仍然开放的 Incident，用于把同一条根因的后续归因并进去。 */
    fun findOpenIncidentIdByTitle(@Param("title") title: String): Long?

    /** 新建一个 Incident；主键由入参 PO 的 `id` 回填。 */
    fun insertIncident(row: RcaIncidentInsertPo): Int

    /**
     * 把归因行关联到 Incident。
     *
     * `WHERE` 里**再判一次归因等级**：调用方已经判过，但库上的
     * `chk_episode_attribution_incident_link` 是最后一道防线，
     * 这里让它以「影响行数为 0」的形式先一步生效，而不是等约束报错。
     */
    fun linkAttributionToIncident(
        @Param("attributionRowId") attributionRowId: Long,
        @Param("incidentId") incidentId: Long
    ): Int

    /** 该任务是否已经有 RCA 沉淀的知识候选；命中则返回已有主键。 */
    fun findKnowledgeCandidateId(@Param("sourceRcaId") sourceRcaId: Long): Long?

    /** 新建知识候选。`knowledge_status` 由语句固定为 `DRAFT`，不开放给调用方。 */
    fun insertKnowledgeCandidate(row: RcaKnowledgeCandidateInsertPo): Int
}
