package com.feedback.agent.rca.intake

import java.time.LocalDateTime

/**
 * 一条待接入的反馈来源。
 *
 * 与 `com.feedback.model.po.rca.RcaIntakeCandidatePo` 的区别是**有没有数据库形状**：
 * PO 的字段名对齐列名，由 MyBatis 注入；本类型只描述接入需要的语义。
 * 两者分开，[com.feedback.agent.rca.episode.RawFeedbackSignal] 的组装就看不到列名，
 * 「多读了一列」这件事也就不可能悄悄影响会话化口径。
 *
 * 端口与类型放在 `agent/rca/intake/` 而不是 `repository/`，是照本包既有的做法
 * （见 `graph/RcaPersistNode.kt` 的 `RcaPersistencePort`、`graph/ContextBuildNode.kt` 的
 * `EpisodeFactsPort`）：RCA 的端口贴在**用到它的那个节点/编排**旁边，
 * 于是「谁需要这份数据、需要哪些字段」在同一处可读。实现仍在 `repository/impl/rca/`。
 */
data class RcaIntakeCandidate(
    val sourceId: Long,
    val sourceChannel: String,
    val sourceRecordId: String,
    /** 业务发生时间。列上是 NOT NULL，这里可空只是不让列约束的变化变成静默漂移。 */
    val eventTime: LocalDateTime?,
    val problemType: String?,
    val title: String?,
    val description: String?,
    /** 应用 UUID 快照的小写十六进制；为 null 表示应用身份尚未解析。 */
    val appPublicIdHex: String?,
    /**
     * 数据归属租户，取该来源解析到的应用身份所属租户（V245）。
     *
     * 它直接进入 `RawFeedbackSignal.tenantKey`，因而是问题对象分区键的一部分——
     * 租户在这里不是一个「顺便带上的标签」，而是决定两个来源是否属于同一个问题对象。
     */
    val tenantKey: String
)

/**
 * 接入候选读取端口。
 *
 * **只有读方法**。接入的写入（问题对象、成员、工作项、任务）全部在
 * [com.feedback.agent.rca.episode.EpisodeIntakeService] 里，并且已经由唯一键保证幂等；
 * 在这里再开一个写口子就会出现两处都能建问题对象的分叉。
 */
fun interface RcaIntakeCandidatePort {

    /**
     * 按来源 ID 游标取一批候选。
     *
     * @param dataEnvironment 数据环境，作为查询条件而非事后过滤。
     */
    fun findAfter(afterSourceId: Long, dataEnvironment: String, limit: Int): List<RcaIntakeCandidate>
}
