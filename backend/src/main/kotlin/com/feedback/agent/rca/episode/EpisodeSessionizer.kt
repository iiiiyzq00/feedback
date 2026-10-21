package com.feedback.agent.rca.episode

import com.feedback.agent.rca.domain.EpisodeJoinDecision
import com.feedback.agent.rca.domain.EpisodeJoinReason
import com.feedback.agent.rca.domain.EpisodeMembershipAction
import com.feedback.agent.rca.domain.EpisodeSessionPolicy
import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.EpisodeSignal
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.TrustedIdentityLevel
import java.time.LocalDateTime

/**
 * Episode 会话化判定器。
 *
 * 输入是「已有问题对象的现状 + 一条新到的原始信号 + 当前时间」，输出是一条归并决定。
 * 之所以做成纯函数：会话化是影响面统计的地基，必须可以被固定夹具逐条复算，
 * 不能依赖数据库状态或上下文。
 *
 * 三个必须讲清楚的口径：
 *
 * 1. **分区键不是设备**。分区是 `租户 + 应用 + 标准化问题键 + 身份指纹`。
 *    按设备 group by 会把「同一设备的不同问题」错误合并；而 414 案例里
 *    「课程视频黑屏」「资源加载失败」「鉴权异常」三种表述能归到一起，
 *    靠的是问题键和错误签名，不是设备。
 *
 * 2. **不活跃判定用事件时间，重开窗口用到达时间**。事件时间决定会话边界（因为迟到数据
 *    的先后顺序才是事实顺序）；到达时间决定「还能不能补」。混用这两个时间会导致
 *    要么迟到数据被静默丢弃，要么历史结论被反复改写。
 *
 * 3. **重复端点仍然保留**。同一设备连提四次，四次原始信号全部落库，
 *    只是 [ImpactCounts.distinctDeviceCount] 计一、[ImpactCounts.repeatCount] 计四。
 *    去重影响面和重复次数是两个指标，不能被压成一个数。
 */
class EpisodeSessionizer(
    private val policy: EpisodeSessionPolicy = EpisodeSessionPolicy.DEFAULT
) {

    /**
     * 判定一条信号如何归属。
     *
     * @param existing 现有问题对象快照；为 null 表示该分区还没有问题对象。
     * @param signal 待归并的原始信号。
     * @param arrivalTime 信号到达平台的时间；重开窗口以它为基准，不由信号自带。
     */
    fun decide(
        existing: EpisodeSnapshot?,
        signal: EpisodeSignal,
        arrivalTime: LocalDateTime
    ): EpisodeJoinDecision {
        if (existing == null) {
            return EpisodeJoinDecision(
                sourceId = signal.sourceId,
                action = EpisodeMembershipAction.CREATE_NEW_EPISODE,
                joinReason = null,
                membershipRevision = 0,
                duplicateEndpoint = false,
                explanation = "分区 ${partitionLabel(signal)} 尚无问题对象，按事件时间开新会话"
            )
        }

        // 身份等级不同不得互相冒充：低等级信号不能并进高等级会话，反之亦然。
        if (existing.identityLevel != signal.identityLevel) {
            return newGeneration(
                signal,
                "身份等级由 ${existing.identityLevel.storageCode} 变为 ${signal.identityLevel.storageCode}，" +
                    "不同等级不可互相代表，开启新 generation"
            )
        }

        // 问题键或错误签名的实质变化优先于时间判定：换了故障就不该并进旧会话。
        if (signatureChanged(existing, signal)) {
            return newGeneration(
                signal,
                "错误签名由 ${existing.errorSignature ?: "未归一"} " +
                    "变为 ${signal.errorSignature ?: "未归一"}，" +
                    "按策略开启新 generation 而不静默并入旧会话"
            )
        }

        // 事件时间早于会话开启时间：这是回填到更早时点的数据，边界本身已经确定，
        // 只能追加 revision，不能重新定义会话起点（否则影响面窗口会被倒着扩大）。
        if (signal.eventTime.isBefore(existing.sessionOpenedAt)) {
            return backfill(
                existing,
                signal,
                "事件时间早于会话开启时间，按迟到数据追加 revision，不重定义会话边界"
            )
        }

        val gapStart = existing.sessionLastEventAt

        // 事件时间落在不活跃阈值内：正常同会话。
        if (!signal.eventTime.isAfter(gapStart.plus(policy.inactivityGap))) {
            return joinExisting(existing, signal, EpisodeJoinReason.SESSION_GAP, "事件时间落在不活跃阈值内")
        }

        // 事件时间超出了阈值，但还在重开窗口内：允许追加 revision，历史行保留为 SUPERSEDED。
        val reopenDeadline = gapStart.plus(policy.inactivityGap).plus(policy.reopenWindow)
        if (!arrivalTime.isAfter(reopenDeadline)) {
            return backfill(
                existing,
                signal,
                "迟到事件：事件时间超出不活跃阈值，但到达时间仍在重开窗口内，追加 membership revision"
            )
        }

        return newGeneration(
            signal,
            "事件时间超出不活跃阈值且到达时间已过重开窗口（截止 $reopenDeadline），" +
                "开启新 generation"
        )
    }

    /**
     * 新会话第一个成员的影响面计数。
     *
     * 单独成方法而不是让调用方自己拼一个 [ImpactCounts]：首条信号与后续信号的计数规则
     * **必须是同一条**（每来一条重复数加一、只有授权设备计入设备数、只有账号/联系人计入用户数）。
     * 分两处写，两处迟早会不一样，而「影响 N 人」这个数字一旦有两套口径，
     * 事后没有任何办法判断某个数字是按哪套算出来的。
     */
    fun initialImpact(signal: EpisodeSignal): ImpactCounts = ImpactCounts(
        repeatCount = 1,
        distinctUserCount = if (countsAsUser(signal.identityLevel)) 1 else 0,
        distinctDeviceCount = if (signal.deviceFingerprint != null) 1 else 0
    )

    /**
     * 增量更新影响面计数。三个数字分开算，任一时刻都不得互相替代或相加。
     *
     * - [ImpactCounts.repeatCount]：每来一条信号就加一，表达「重复上报了多少次」。
     * - [ImpactCounts.distinctDeviceCount]：只按设备指纹去重；拿不到设备指纹的信号不计数。
     * - [ImpactCounts.distinctUserCount]：只按可信账号/渠道联系人的身份指纹去重。
     *
     * 举例：同一授权设备连提四次，得到 repeatCount 为 4、distinctDeviceCount 为 1、
     * distinctUserCount 为 0——三个数字各说一件事，相加得到的「影响 5 个」没有任何含义。
     */
    fun applyImpact(existing: EpisodeSnapshot, signal: EpisodeSignal): ImpactCounts {
        val identityKey = signal.identityKey()
        val repeatCount = existing.impact.repeatCount + 1

        val deviceCount = when {
            signal.deviceFingerprint == null -> existing.impact.distinctDeviceCount
            existing.deviceFingerprints.contains(signal.deviceFingerprint) -> existing.impact.distinctDeviceCount
            else -> existing.impact.distinctDeviceCount + 1
        }

        val userCount = when {
            !countsAsUser(signal.identityLevel) -> existing.impact.distinctUserCount
            existing.identityFingerprints.contains(identityKey) -> existing.impact.distinctUserCount
            else -> existing.impact.distinctUserCount + 1
        }

        return ImpactCounts(
            repeatCount = repeatCount,
            distinctUserCount = userCount,
            distinctDeviceCount = deviceCount
        )
    }

    /**
     * 推导归并后的会话状态。
     *
     * 事件时间越过不活跃阈值说明会话曾经断开过，此时被重新加入即 [EpisodeSessionState.REOPENED]；
     * 这一状态会让窗口重算知道「本窗口内存在断点」，而不是把断点前后的量直接加总。
     *
     * **状态只升不降**：已经 [EpisodeSessionState.REOPENED] 的会话不会因为随后一条更早的
     * 迟到事件退回 [EpisodeSessionState.OPEN]。断点一旦在窗口里出现就不会消失，
     * 而回退会把「本窗口存在断点」这条事实抹掉——窗口重算随后就会把断点前后的量
     * 当成连续量加总，得到一个偏大的影响面，且没有任何痕迹能解释它为什么会偏大。
     */
    fun resolveSessionState(existing: EpisodeSnapshot, signal: EpisodeSignal): EpisodeSessionState = when {
        existing.sessionState == EpisodeSessionState.REOPENED -> EpisodeSessionState.REOPENED
        signal.eventTime.isAfter(existing.sessionLastEventAt.plus(policy.inactivityGap)) ->
            EpisodeSessionState.REOPENED

        else -> EpisodeSessionState.OPEN
    }

    /**
     * 该身份等级是否计入可信用户数。
     *
     * 设备与匿名信号不贡献用户数：设备指纹不能当成账号来用，
     * 否则同一台设备的四次上报会被算成四个「用户」。
     */
    private fun countsAsUser(level: TrustedIdentityLevel): Boolean = when (level) {
        TrustedIdentityLevel.ACCOUNT, TrustedIdentityLevel.CHANNEL_CONTACT -> true
        TrustedIdentityLevel.AUTHORIZED_DEVICE, TrustedIdentityLevel.ANONYMOUS -> false
    }

    private fun signatureChanged(existing: EpisodeSnapshot, signal: EpisodeSignal): Boolean {
        val incoming = signal.errorSignature ?: return false
        val current = existing.errorSignature ?: return false
        return incoming != current
    }

    private fun isDuplicateEndpoint(existing: EpisodeSnapshot, signal: EpisodeSignal): Boolean {
        val key = signal.endpointKey ?: return false
        return existing.endpointKeys.contains(key)
    }

    private fun joinExisting(
        existing: EpisodeSnapshot,
        signal: EpisodeSignal,
        reason: EpisodeJoinReason,
        explanation: String
    ): EpisodeJoinDecision = EpisodeJoinDecision(
        sourceId = signal.sourceId,
        action = EpisodeMembershipAction.JOIN,
        joinReason = reason,
        membershipRevision = existing.memberRevision,
        duplicateEndpoint = isDuplicateEndpoint(existing, signal),
        explanation = explanation
    )

    private fun backfill(existing: EpisodeSnapshot, signal: EpisodeSignal, explanation: String): EpisodeJoinDecision =
        EpisodeJoinDecision(
            sourceId = signal.sourceId,
            action = EpisodeMembershipAction.APPEND_REVISION,
            joinReason = EpisodeJoinReason.LATE_APPEND,
            membershipRevision = existing.memberRevision + 1,
            duplicateEndpoint = isDuplicateEndpoint(existing, signal),
            explanation = explanation
        )

    private fun newGeneration(signal: EpisodeSignal, explanation: String): EpisodeJoinDecision = EpisodeJoinDecision(
        sourceId = signal.sourceId,
        action = EpisodeMembershipAction.OPEN_NEW_GENERATION,
        joinReason = EpisodeJoinReason.SIGNATURE_CHANGED,
        membershipRevision = 0,
        duplicateEndpoint = false,
        explanation = explanation
    )

    private fun partitionLabel(signal: EpisodeSignal): String =
        "${signal.tenantKey}/${signal.appId}/${signal.problemKey}/${signal.identityKey()}"

    /** 身份指纹是二进制，日志与分区标签中统一用 Base64 呈现，避免日志里出现不可见字符。 */
    private fun EpisodeSignal.identityKey(): String = identityFingerprint.toIdentityKey()
}
