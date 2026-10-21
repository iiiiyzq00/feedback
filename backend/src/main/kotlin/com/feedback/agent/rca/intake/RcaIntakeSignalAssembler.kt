package com.feedback.agent.rca.intake

import com.feedback.agent.rca.episode.ContactHints
import com.feedback.agent.rca.episode.EpisodeEndpointKey
import com.feedback.agent.rca.episode.RawFeedbackSignal
import com.feedback.model.auth.TenantKeys
import com.feedback.service.cluster.ErrorSignatureNormalizer
import org.springframework.stereotype.Component

/**
 * 把一条数据库候选行组装成 [RawFeedbackSignal]。
 *
 * ## 这个类只做映射，不做判断
 *
 * 它是纯函数式的：同一行同样的输入永远得到同样的信号。所有「算不算同一个问题」
 * 的判断都在 [com.feedback.agent.rca.episode.EpisodeSessionizer] 里，所有「这条信号代表谁」
 * 的判断都在 [com.feedback.agent.rca.episode.EpisodeIdentityResolver] 里。
 * 接入层一旦开始自己判重或自己定级，就会出现两套口径。
 *
 * ## 组装不出来时返回 null，不猜
 *
 * 返回 null 表示这一行缺少必需字段，调度侧会记一个稳定原因码并跳过它。
 * 代替方案是「给个默认值让它过关」，但那会让一条信息不全的记录静默变成
 * 一个看起来正常的问题对象成员，事后无法分辨它是真的还是被补齐的。
 */
@Component
class RcaIntakeSignalAssembler(
    private val normalizer: ErrorSignatureNormalizer
) {

    /**
     * 组装一条信号。
     *
     * @return 组装结果；缺必需字段时返回 null，原因码见 [rejectReason]。
     */
    fun assemble(row: RcaIntakeCandidate): RawFeedbackSignal? {
        if (rejectReason(row) != null) return null
        val appId = appIdOf(row)
        val signature = signatureOf(row)
        return RawFeedbackSignal(
            sourceId = row.sourceId,
            // 租户取自该来源解析到的应用身份，**不是**常量：它参与 uk_episode_partition，
            // 写错会让两个租户的证据并进同一个问题对象，而那种错误的形状恰好是
            // 「影响面看起来更大」——最不容易被当成 bug 的那种。
            // 空值退到 DEFAULT 而不是报错：SQL 侧已经 COALESCE 过，这里再兜一次是因为
            // 「租户取不到」的正确解释是「还没接入租户体系」而不是「这条来源不可用」；
            // 在这里拒绝接入会让一整批来源卡在游标上，代价远大于按兼容租户收下。
            tenantKey = row.tenantKey.ifBlank { TenantKeys.DEFAULT },
            appId = appId,
            problemKey = problemKeyOf(signature, row.problemType, appId),
            errorSignature = signature,
            eventTime = row.eventTime!!,
            // 身份线索为空是**如实**的，不是没接：本平台的渠道表里没有手机号或邮箱
            // （`t_channel_jira` 只有 reporter/creator 这类账号名，不是联系方式），
            // 而 verifiedAccountId 必须来自会话令牌校验——导入的历史记录没有令牌可校验。
            // 于是 EpisodeIdentityResolver 会按「无任何身份线索」处理，让每条来源自成一会话。
            // 这看起来聚合度低，但它不会制造「影响 N 人」的假问题对象。
            hints = ContactHints(),
            // 端点键必须与 `EpisodeSessionMapper.xml` 的 findMemberEndpointKeys 用同一种拼法，
            // 否则「重复端点」的判定会在库与内存之间分叉。
            endpointKey = EpisodeEndpointKey.of(row.sourceChannel, row.sourceRecordId)
        )
    }

    /** 返回这一行不能接入的稳定原因码；可接入时为 null。 */
    fun rejectReason(row: RcaIntakeCandidate): String? = when {
        row.sourceId <= 0 -> "RCA_INTAKE_SOURCE_ID_INVALID"
        row.sourceChannel.isBlank() -> "RCA_INTAKE_SOURCE_CHANNEL_MISSING"
        row.sourceRecordId.isBlank() -> "RCA_INTAKE_SOURCE_RECORD_ID_MISSING"
        // `t_feedback_source.source_event_time` 是 NOT NULL，所以这一支在库里不会命中；
        // 保留它是为了不让「列约束变了」变成一次静默的边界漂移。
        row.eventTime == null -> "RCA_INTAKE_EVENT_TIME_MISSING"
        else -> null
    }

    /**
     * 应用标识：优先用主问题事实里的应用 UUID 快照，取不到就记 `UNKNOWN`。
     *
     * **不用渠道上下文猜**：猜出来的应用会把不同应用的同类故障并进同一个分区，
     * 而并错的方向恰好是「影响面变大」，看起来更像一个值得处理的问题。
     */
    private fun appIdOf(row: RcaIntakeCandidate): String =
        row.appPublicIdHex?.takeIf(String::isNotBlank)?.take(APP_ID_MAX) ?: UNKNOWN_APP_ID

    /**
     * 错误签名：复用集群域已有的归一器，**不在这里实现第二套**。
     *
     * 两套归一器的后果不是「结果略有不同」，而是同一个故障在簇里是一个签名、
     * 在 RCA 里是另一个签名，于是按签名对齐两边数据的任何分析都会静默对不上。
     */
    private fun signatureOf(row: RcaIntakeCandidate): String? {
        val text = listOfNotNull(row.title, row.description)
            .filter(String::isNotBlank)
            .joinToString("\n")
        return text.takeIf(String::isNotBlank)?.let(normalizer::normalize)
    }

    /**
     * 问题键：归一成功就用签名，失败时退到 `UNCLASSIFIED:<问题类型>:<应用>`。
     *
     * `t_feedback_episode.problem_key` 是 NOT NULL，所以必须有降级值；
     * 降级值里带上问题类型与应用，是为了让「归一失败」而不是「所有归一失败都一样」——
     * 全退成同一个常量会让这些来源在问题键维度上被错误地看成同一类。
     *
     * 签名最长 500（归一器自己截过），而本列只有 200，所以必须截断。
     * 截断不丢信息：完整签名另外落在 `error_signature VARCHAR(500)`，
     * 而开新代的判定同时看两者（`EpisodeSessionizer.signatureChanged`）。
     */
    private fun problemKeyOf(signature: String?, problemType: String?, appId: String): String =
        (signature ?: "UNCLASSIFIED:${problemType?.takeIf(String::isNotBlank) ?: "UNKNOWN"}:$appId")
            .take(PROBLEM_KEY_MAX)

    companion object {
        /** 应用身份未解析时的占位，**不是**一个真实应用。 */
        const val UNKNOWN_APP_ID = "UNKNOWN"

        private const val APP_ID_MAX = 50
        private const val PROBLEM_KEY_MAX = 200
    }
}
