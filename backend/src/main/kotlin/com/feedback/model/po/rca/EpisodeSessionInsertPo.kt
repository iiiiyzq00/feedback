package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * `t_feedback_episode` 的插入行。
 *
 * [id] 由数据库自增后回填（`useGeneratedKeys`），所以它必须是可变的；插入前为 0。
 * 其余字段的默认值只为让 MyBatis 能按无参构造 + setter 注入，**不具备业务含义**：
 * 漏填一个字段会在 `NOT NULL` 或 CHECK 约束上失败，而不是静默写进一个默认值。
 *
 * 本类型只在写入路径上一次性使用（构造 → 插入 → 取回主键），**不参与比较**，
 * 因此与 `ClusterPo` 一样不重写 equals/hashCode。若将来要按值比较，注意
 * [identityFingerprint] 是 [ByteArray]，自动生成的实现按引用比较，必须先改成 content 比较。
 */
data class EpisodeSessionInsertPo(
    var publicId: String = "",
    var tenantKey: String = "",
    var appId: String = "",
    var problemKey: String = "",
    var identityFingerprint: ByteArray = ByteArray(0),
    var generation: Int = 0,
    var identityLevel: String = "",
    var errorSignature: String? = null,
    var sessionState: String = "",
    var sessionOpenedAt: LocalDateTime? = null,
    var sessionLastEventAt: LocalDateTime? = null,
    var repeatCount: Int = 0,
    var distinctUserCount: Int = 0,
    var distinctDeviceCount: Int = 0,
    var memberRevision: Long = 0,
    var policyVersion: String = "",
    var id: Long = 0
)
