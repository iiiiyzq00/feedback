package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * Episode 事实行的数据库形态，由 `RcaEpisodeFactsMapper.loadEpisodeFacts` 的宽表查询产出。
 *
 * 与 [com.feedback.agent.rca.graph.EpisodeFacts] 分开是刻意的：这一层只反映「库里有哪几列」，
 * 而事实装配（缺列怎么降级、哪个字段要回退）留在仓库实现里。合成一个类的话，
 * 列与列之间的推导关系会散进 SQL，改一次落库结构就要同时读 Kotlin 和 XML 才能判断行为。
 *
 * 四个字段**不是直接读同名列**，都在 SQL 里算好再带出来：
 *
 * 1. `clusterMemberCount`：`t_feedback_episode` **没有**成员数列。它的 `repeat_count` 是
 *    「重复上报次数」，与「成员数」是两个口径（V242 的注释专门要求两者禁止相加），
 *    所以只能按 `membership_status='ACTIVE'` 现数。
 * 2. `crashAnrMemberCount`：现象在 `t_feedback_content.problem_type` 上，不在 Episode 上。
 *    取计数而不是布尔，是为了在日志里能看出「有几个成员是崩溃」，而不是只知道「有没有」。
 * 3. `clusterId` / `canonicalSymptom`：簇归属记在 `t_rca_agent_task` 上（接入期为 NULL，
 *    是正常态），簇名要再跳一层 `t_cluster` 才有。
 * 4. `packageName`：取**最早加入的 ACTIVE 成员**的包名。这是确定性选择；
 *    用 `MAX()` 之类的聚合虽然也是确定性的，但「为什么是这一个」没有业务含义可讲。
 *
 * 字段名与列名的对应由 `map-underscore-to-camel-case` 完成（`application.yml:82`），
 * 本类字段顺序不影响任何位置参数构造。
 */
data class RcaEpisodeFactsPo(
    /** `t_feedback_episode.id`。 */
    var episodeId: Long = 0,

    /** `t_feedback_episode.tenant_key`，当前单租户部署恒为 `DEFAULT`。 */
    var tenantKey: String = "",

    /** `t_feedback_episode.app_id`，32 位十六进制应用公开标识。 */
    var appId: String = "",

    /** `t_feedback_episode.problem_key`，分区键；也是簇名缺失时的症状回退值。 */
    var problemKey: String = "",

    /** `t_feedback_episode.error_signature`；为 NULL 表示尚未归一。 */
    var errorSignature: String? = null,

    /** `t_feedback_episode.generation`，问题键或签名明显变化时递增。 */
    var generation: Int = 1,

    /** `t_feedback_episode.session_opened_at`，首个成员的事件时间。 */
    var sessionOpenedAt: LocalDateTime? = null,

    /** `t_feedback_episode.session_last_event_at`，末个成员的事件时间。 */
    var sessionLastEventAt: LocalDateTime? = null,

    /** `t_feedback_episode.repeat_count`，只增；**不与去重人数/设备数相加**。 */
    var repeatCount: Int = 0,

    /** `t_feedback_episode.distinct_user_count`，仅按可信账号/联系人去重。 */
    var distinctUserCount: Int = 0,

    /** `t_feedback_episode.distinct_device_count`，仅按授权设备去重。 */
    var distinctDeviceCount: Int = 0,

    /** ACTIVE 成员数；驱动 `ProblemProfile.multiMember`，即是否要求先看错误签名分布。 */
    var clusterMemberCount: Int = 0,

    /** 现象为 Crash/ANR 的 ACTIVE 成员数；大于 0 即要求必取堆栈。 */
    var crashAnrMemberCount: Int = 0,

    /** 本 Episode 最新一条 RCA 任务上的簇归属；接入期为 NULL。 */
    var clusterId: Long? = null,

    /** `t_cluster.canonical_symptom`；无簇或簇名未固定时为 NULL。 */
    var canonicalSymptom: String? = null,

    /** 最早加入的 ACTIVE 成员所属的应用包名；用于查版本对照基准。 */
    var packageName: String? = null
)
