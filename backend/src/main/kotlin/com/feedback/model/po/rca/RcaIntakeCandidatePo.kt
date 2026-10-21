package com.feedback.model.po.rca

import com.feedback.model.auth.TenantKeys
import java.time.LocalDateTime

/**
 * 一条**待接入**的反馈来源记录，即「原始信号」的数据库形态。
 *
 * 由 `RcaIntakeCandidateMapper.findIntakeCandidatesAfter` 的 JOIN 产出，
 * 覆盖组装 [com.feedback.agent.rca.episode.RawFeedbackSignal] 所需的全部列——
 * **刻意一次取齐**：分多次按需查询会让「同一条来源的业务发生时间」在不同步骤读到不同值，
 * 而事件时间是会话边界的判定基准，读飘了就会凭空多出一次开新代。
 *
 * 字段名与列名的对应由 `map-underscore-to-camel-case` 完成（`application.yml:82`），
 * 所以本类字段顺序不影响任何位置参数构造。
 */
data class RcaIntakeCandidatePo(
    /** `t_feedback_source.id`，同时是接入游标的推进依据。 */
    var sourceId: Long = 0,

    /** `t_feedback_source.source_channel`，与来源原始 ID 一起构成端点键。 */
    var sourceChannel: String = "",

    /** `t_feedback_source.source_record_id`，渠道内的原始业务 ID。 */
    var sourceRecordId: String = "",

    /** `t_feedback_source.source_event_time`，**业务发生时间**，不是入库时间。 */
    var sourceEventTime: LocalDateTime? = null,

    /** `t_feedback_source.app_version`，仅用于事后核对，不参与身份或分区判定。 */
    var appVersion: String? = null,

    /**
     * 数据归属租户，V245 起由 SQL **现算**（来源 → 主问题事实 → 应用身份，未解析时退到 `'DEFAULT'`）。
     *
     * 它从这一行一路传到 `t_feedback_episode.tenant_key`，并参与
     * `uk_episode_partition (tenant_key, app_id, problem_key, identity_fingerprint, generation)`——
     * 也就是说**租户是问题对象分区键的一部分**：两个租户即使应用与问题键完全相同，
     * 也会各自形成一个独立的问题对象，证据不会互串。
     * 这里的默认值只是让「SQL 没给出该列」不至于静默变成空串；正常取值一律由 SQL 提供。
     */
    var tenantKey: String = TenantKeys.DEFAULT,

    /** `t_feedback_content.problem_type`，归一失败时作为问题键的降级成分。 */
    var problemType: String? = null,

    /** `t_feedback_content.title`，与描述一起送归一器提取错误签名。 */
    var contentTitle: String? = null,

    /** `t_feedback_content.description`，同上。 */
    var contentDescription: String? = null,

    /**
     * `t_feedback_primary_problem_fact.application_public_id_snapshot` 的小写十六进制。
     *
     * 列本身是 `BINARY(16)`，用 `LOWER(HEX(...))` 取出而不是在 Kotlin 侧读字节数组：
     * 应用标识要落进 `t_feedback_episode.app_id VARCHAR(50)`，十六进制 32 字符正好放得下，
     * 而字节数组的 `toString()` 会给出 `[B@1a2b3c` 这种既不稳定也不可读的值。
     *
     * 为 NULL 表示该来源的应用身份尚未解析（`application_resolution_status` 非 `RESOLVED`），
     * 此时按 `UNKNOWN` 处理，不猜一个应用出来。
     */
    var appPublicIdHex: String? = null
)
