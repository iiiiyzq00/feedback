package com.feedback.agent.rca.episode

import com.feedback.agent.rca.domain.EpisodeJoinDecision
import com.feedback.agent.rca.domain.EpisodeJoinReason
import com.feedback.agent.rca.domain.EpisodeMembershipAction
import com.feedback.agent.rca.domain.EpisodeSessionPolicy
import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.EpisodeSignal
import com.feedback.agent.rca.domain.ImpactCounts
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 一条刚到平台的原始反馈信号。
 *
 * 与 [EpisodeSignal] 的区别是**有没有解析过身份**：这个类型带的是原始线索
 * （[ContactHints]），等级与指纹由 [EpisodeIdentityResolver] 推导；
 * [EpisodeSignal] 带的是已定级的身份，是会话化判定的输入。
 * 两个类型分开，是为了让「等级由谁决定」在类型上就写清楚——接入层拿不到决定权。
 */
data class RawFeedbackSignal(
    val sourceId: Long,
    val tenantKey: String,
    val appId: String,
    val problemKey: String,
    val errorSignature: String?,
    val eventTime: LocalDateTime,
    val hints: ContactHints,
    /**
     * 同一端点重复上报的判定键，通常由来源渠道与来源原始 ID 组成。
     * 为空表示无法判定重复端点——此时一律按不重复计，而不是猜一个键出来。
     */
    val endpointKey: String? = null
)

/**
 * 一次接入的处理结果。
 *
 * 六个字段都是**运维与复核会问的问题**：落到哪个问题对象、第几代、做了什么动作、
 * 是不是重复投递、工作项与任务有没有建起来。返回一个布尔量会让这些问题全部要靠翻日志回答。
 */
data class EpisodeIntakeOutcome(
    val episodeId: Long,
    val generation: Int,
    val action: EpisodeMembershipAction,
    /** 同一条 source 被重复投递：已幂等命中，影响面计数未重复累加。 */
    val duplicateDelivery: Boolean,
    val workItemId: Long,
    val workItemCreated: Boolean,
    val taskKey: String,
    val taskCreated: Boolean,
    val impact: ImpactCounts,
    val explanation: String
)

/**
 * 反馈信号接入：会话化 → 落事故对象 → 建工作项 → 投递 RCA 任务。
 *
 * 这是「原始信号」到「可处理问题对象」之间唯一的一段编排，它本身**不含业务判断**：
 * 定级在 [EpisodeIdentityResolver]，归并在 [EpisodeSessionizer]，落库在
 * [EpisodeSessionStore]。这里只负责把它们按顺序串起来，并把每一步的结果如实交代出去。
 *
 * ## 顺序是有约束的
 *
 * 1. **先解析身份，再算分区。** 分区键含身份指纹，指纹来自定级结果（HMAC 的输入里带类型），
 *    所以「先定级、再分区」不是风格问题——顺序反了会拿着还没定下来的指纹去找问题对象。
 * 2. **先追加成员，再更新计数。** 成员表上的唯一键是幂等的唯一防线（见下），
 *    计数更新必须排在它后面：插入被判定为重复时直接返回，一个数字都不会动。
 * 3. **先落 Episode，再投递任务。** RCA 任务带外键指向 Episode；反过来写会让任务指向
 *    一个不存在的问题对象，而那个失败发生在事务之外，没人会看到。
 *
 * ## 至少一次投递与幂等
 *
 * 接入链路是**至少一次**语义，同一条 source 可能被投递两次。幂等点只有两个，都在唯一键上：
 * `uk_episode_member_revision` 挡住重复成员，`uk_rca_agent_task_key` 挡住重复任务。
 * 不靠「先查一次再写」：两次投递可能并发，查与写之间没有东西保证结论仍然成立。
 *
 * ## 已知边界（本次未做，需要时再补）
 *
 * - **重开会话不重新发起调查。** 任务幂等键是「Episode + 策略版本」，
 *   所以一个已终态的 Episode 被迟到数据重开后不会再有第二个 RCA 任务。
 *   重开语义需要与「终态任务能否复算」一起定，属于独立的一档，不在接入编排里顺手决定。
 * - **聚类结果不参与接入。** `cluster_id` 是异步产出的，接入期一律为空；
 *   「联合调查候选」是取证的输入，不是创建问题对象的条件。
 * - **并发并入同一 Episode 会丢一次计数更新。** 计数是「读—算—写」：
 *   两条**不同 source** 的信号同时并入时，两边都读到同一个旧值、都写回同一个新值。
 *   唯一键挡不住它——它们本来就是两条不同的信号（挡得住的是同一条 source 的重投）。
 *   彻底修掉需要按 Episode 串行化（行锁或版本号），属于独立的一档；
 *   本次只在 `updateEpisode` 里保证 末次事件时间与修订号只前进不回退。
 *   **注意这条边界与本文档「至少一次投递」不同**：重投是同一 source，走幂等命中，不受影响。
 * - **并发首次接入同一新分区会有一方失败。** 两个事务同时判定「分区尚无问题对象」，
 *   都去建 generation=1，`uk_episode_partition` 会让后到者抛唯一键冲突。
 *   这里**不做重试**：接入链路是至少一次投递，让整条信号回滚、由投递方重投即可
 *   ——那时会话已存在，重投会走并入路径。吞掉冲突反而会让两条信号各建一个会话，
 *   于是同一个问题对象被拆成两个，影响面各自减半。
 */
@Service
class EpisodeIntakeService(
    private val identities: EpisodeIdentityResolver,
    private val sessionizer: EpisodeSessionizer,
    private val store: EpisodeSessionStore,
    private val dispatch: WorkItemDispatchPort,
    private val policy: EpisodeSessionPolicy = EpisodeSessionPolicy.DEFAULT
) {

    /**
     * 接收一条原始信号。
     *
     * @param arrivalTime 信号到达平台的时间。重开窗口以它为基准，**不用信号自带的事件时间**：
     *        事件时间用来定会话边界，到达时间用来定「还能不能补」，混用会让迟到数据
     *        要么被静默丢弃、要么把历史结论反复改写。
     *
     * 整个方法是一个事务：Episode 行、成员行与计数必须同时生效或同时不生效，
     * 否则会出现「成员在里面、计数没加」的问题对象——影响面偏小且无法事后分辨
     * 是漏算了还是真的只报了这么多次。
     *
     * 工作项与任务投递也在这个事务里。代价是它们失败会连带回滚已经算好的影响面，
     * 收益是不需要「扫出没有工作项的 Episode」这种补偿任务——
     * [WorkItemDispatchPort] 的端口拆分保留了将来做那种补偿的空间，本次没有实现它。
     *
     * 注解只在被 Spring 代理调用时生效。`rcaEpisodeSessionization` 校验入口直接 new 出本类，
     * 那时它就是一个普通方法——所以口径不能依赖事务，事务只是额外的一道保证。
     */
    @Transactional
    fun accept(signal: RawFeedbackSignal, arrivalTime: LocalDateTime): EpisodeIntakeOutcome {
        val identity = identities.resolve(signal.sourceId, signal.hints)
        val partition = EpisodePartition(
            tenantKey = signal.tenantKey,
            appId = signal.appId,
            problemKey = signal.problemKey,
            identityFingerprint = identity.fingerprint
        )
        val episodeSignal = EpisodeSignal(
            sourceId = signal.sourceId,
            tenantKey = signal.tenantKey,
            appId = signal.appId,
            problemKey = signal.problemKey,
            errorSignature = signal.errorSignature,
            identityLevel = identity.level,
            identityFingerprint = identity.fingerprint,
            eventTime = signal.eventTime,
            deviceFingerprint = identity.deviceFingerprint,
            endpointKey = signal.endpointKey
        )

        // 分区里已经含了身份类型，所以同一指纹配不同等级在当前定级规则下不可能出现；
        // `EpisodeSessionizer` 里那条等级校验仍然保留，因为它是纯函数，安全性不能依赖
        // 「调用方恰好不会那么传」——换一套定级规则它就会变成必经分支。
        val existing = store.findActive(partition)
        val decision = sessionizer.decide(existing, episodeSignal, arrivalTime)

        return when (decision.action) {
            EpisodeMembershipAction.CREATE_NEW_EPISODE,
            EpisodeMembershipAction.OPEN_NEW_GENERATION -> openEpisode(partition, existing, episodeSignal, decision)

            EpisodeMembershipAction.JOIN,
            EpisodeMembershipAction.APPEND_REVISION ->
                joinExisting(
                    existing = requireNotNull(existing) {
                        "判定为 ${decision.action} 却没有现有问题对象：分区 ${partition.label}"
                    },
                    signal = episodeSignal,
                    decision = decision
                )
        }
    }

    /** 新建问题对象（首次出现的分区，或同一分区上换了故障签名的新 generation）。 */
    private fun openEpisode(
        partition: EpisodePartition,
        existing: EpisodeSnapshot?,
        signal: EpisodeSignal,
        decision: EpisodeJoinDecision
    ): EpisodeIntakeOutcome {
        // generation 只增不减，且必须与分区键一起唯一（见 uk_episode_partition）。
        // 首个 generation 从 1 开始：0 会让「generation 是第几代」在人工复核时读不通。
        val generation = (existing?.generation ?: 0) + 1
        val impact = sessionizer.initialImpact(signal)
        val episodeId = store.createEpisode(
            NewEpisodeSpec(
                partition = partition,
                generation = generation,
                identityLevel = signal.identityLevel,
                errorSignature = signal.errorSignature,
                sessionState = EpisodeSessionState.OPEN,
                // 会话起点与末次事件都用**事件时间**：影响面窗口是围绕现象发生时刻算的，
                // 用入库时间会让一批补录数据的窗口整体平移到现在。
                sessionOpenedAt = signal.eventTime,
                sessionLastEventAt = signal.eventTime,
                impact = impact,
                memberRevision = decision.membershipRevision,
                policyVersion = policy.policyVersion
            )
        )
        store.appendMember(
            EpisodeMemberAppend(
                episodeId = episodeId,
                sourceId = signal.sourceId,
                membershipRevision = decision.membershipRevision,
                identityLevel = signal.identityLevel,
                // 新对象的第一条成员没有「并入」可言：join_reason 必须是问题键命中，
                // 而 CHECK 约束里也没有「新建」这一档可用。
                joinReason = EpisodeJoinReason.PROBLEM_KEY_HIT.storageCode,
                eventTime = signal.eventTime,
                duplicateEndpoint = false
            )
        )

        val workItem = dispatch.ensureWorkItem(episodeId, signal.eventTime, signal.eventTime)
        val taskKey = taskKeyOf(episodeId)
        // 聚类结果此时还不存在，cluster_id 留空是事实而不是偷懒：它在取证阶段才被用上。
        val task = dispatch.enqueueRcaTask(episodeId, taskKey, clusterId = null)

        return EpisodeIntakeOutcome(
            episodeId = episodeId,
            generation = generation,
            action = decision.action,
            duplicateDelivery = false,
            workItemId = workItem.workItemId,
            workItemCreated = workItem.created,
            taskKey = task.taskKey,
            taskCreated = task.created,
            impact = impact,
            explanation = decision.explanation
        )
    }

    /** 并入已有问题对象：追加成员，再按增量更新计数与会话状态。 */
    private fun joinExisting(
        existing: EpisodeSnapshot,
        signal: EpisodeSignal,
        decision: EpisodeJoinDecision
    ): EpisodeIntakeOutcome {
        val append = store.appendMember(
            EpisodeMemberAppend(
                episodeId = existing.episodeId,
                sourceId = signal.sourceId,
                membershipRevision = decision.membershipRevision,
                identityLevel = signal.identityLevel,
                joinReason = (decision.joinReason ?: EpisodeJoinReason.SESSION_GAP).storageCode,
                eventTime = signal.eventTime,
                duplicateEndpoint = decision.duplicateEndpoint
            )
        )

        // 幂等命中：这条 source 已经在这个 Episode 上，计数一个都不能再动。
        // 这正是「先追加成员、后更新计数」的原因——重复投递在这里就被挡住了，
        // 而不是靠调用方记得在更新前先判一次重。
        if (append == MemberAppendOutcome.ALREADY_PRESENT) {
            // 这里只是取回工作项标识，不触碰它：重复投递**什么都不该改**，
            // 包括末次出现时间。带事件时间进来会让一条重投把 last_seen_at 拨回去。
            val workItem = dispatch.ensureWorkItem(
                episodeId = existing.episodeId,
                firstSeenAt = existing.sessionOpenedAt,
                lastSeenAt = existing.sessionLastEventAt
            )
            return EpisodeIntakeOutcome(
                episodeId = existing.episodeId,
                generation = existing.generation,
                action = decision.action,
                duplicateDelivery = true,
                workItemId = workItem.workItemId,
                workItemCreated = workItem.created,
                taskKey = taskKeyOf(existing.episodeId),
                taskCreated = false,
                impact = existing.impact,
                explanation = "source ${signal.sourceId} 已是该问题对象的成员，" +
                    "按重复投递处理，未重复计数"
            )
        }

        val impact = sessionizer.applyImpact(existing, signal)
        val sessionState = sessionizer.resolveSessionState(existing, signal)
        // 末次事件时间只前进不回退。迟到事件的 event_time 更早，若直接覆盖，
        // 不活跃判定的基准与下一次重开窗口的截止都会被往回拨，于是同一段断点会被反复重开。
        val lastEventAt = maxOf(existing.sessionLastEventAt, signal.eventTime)
        store.updateEpisode(
            existing.episodeId,
            EpisodeUpdate(
                impact = impact,
                sessionState = sessionState,
                sessionLastEventAt = lastEventAt,
                memberRevision = decision.membershipRevision,
                // 归一化是异步的：会话创建时签名可能还没算出来，之后才到。
                // 到了就补上，但**不**因此开新 generation——否则每条迟到的归一化都会撕出一个新会话。
                fillErrorSignature = signal.errorSignature?.takeIf { existing.errorSignature == null }
            )
        )
        dispatch.touchWorkItem(
            episodeId = existing.episodeId,
            lastSeenAt = lastEventAt,
            reopened = sessionState == EpisodeSessionState.REOPENED
        )
        // 并入路径上工作项必然已经存在（建问题对象时就建了）。这里取回标识而不是新建，
        // created 恒为 false；真正被改的是上面那次 touch。
        val workItem = dispatch.ensureWorkItem(
            episodeId = existing.episodeId,
            firstSeenAt = existing.sessionOpenedAt,
            lastSeenAt = lastEventAt
        )

        return EpisodeIntakeOutcome(
            episodeId = existing.episodeId,
            generation = existing.generation,
            action = decision.action,
            duplicateDelivery = false,
            workItemId = workItem.workItemId,
            workItemCreated = false,
            taskKey = taskKeyOf(existing.episodeId),
            taskCreated = false,
            impact = impact,
            explanation = decision.explanation
        )
    }

    /**
     * RCA 任务的业务幂等键。
     *
     * 组成是「Episode + 策略版本」：同一问题对象在同一版会话化策略下只允许一个活跃任务。
     * 带上策略版本是因为影响面的口径会随策略变——换策略之后旧任务算出的结论
     * 与新口径下的成员集合可能对不上，那时需要的是**重新算**，而不是沿用旧任务。
     */
    private fun taskKeyOf(episodeId: Long): String = "rca:episode:$episodeId:${policy.policyVersion}"
}
