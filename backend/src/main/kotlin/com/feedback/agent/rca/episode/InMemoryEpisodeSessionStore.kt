package com.feedback.agent.rca.episode

import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.TrustedIdentityLevel
import java.time.LocalDateTime

/**
 * [EpisodeSessionStore] 的内存实现。
 *
 * 只用于验证：它把唯一键、appendix 语义与「去重集合从事实回查」这三件事按与 SQL 相同的方式实现，
 * 好让套件能在没有数据库的机器上跑一遍真实路径。它**不是**生产实现，
 * 因此不追求并发正确性——所有方法都不是线程安全的，也不需要是。
 *
 * 有一处必须与 SQL 完全对齐：[findActive] 返回的去重集合。库里它们是两次 join
 * （成员 → t_feedback_identity / t_feedback_source），这里通过 [facts] 补齐。
 * 若替身改用「记下调用方传来的东西」，两边就会在「哪些身份类型算人」上分叉，
 * 而这个分叉会直接表现成影响面计数不一致。
 */
class InMemoryEpisodeSessionStore(
    private val facts: MemberSourceFactsPort = MemberSourceFactsPort.EMPTY
) : EpisodeSessionStore {

    /** 成员行快照，供套件断言「哪条 source 以什么原因、第几个 revision 并入」。 */
    data class MemberRow(
        val episodeId: Long,
        val sourceId: Long,
        val membershipRevision: Long,
        val joinReason: String,
        val duplicateEndpoint: Boolean,
        val eventTime: LocalDateTime
    )

    private class EpisodeRow(
        val id: Long,
        val partition: EpisodePartition,
        val generation: Int,
        val identityLevel: TrustedIdentityLevel,
        var errorSignature: String?,
        var sessionState: EpisodeSessionState,
        val sessionOpenedAt: LocalDateTime,
        var sessionLastEventAt: LocalDateTime,
        var impact: ImpactCounts,
        var memberRevision: Long,
        val policyVersion: String
    ) {
        /** 去重集合：与库里 join 出来的结果同义，见类注释。 */
        val endpointKeys: MutableSet<String> = linkedSetOf()
        val userFingerprintKeys: MutableSet<String> = linkedSetOf()
        val deviceFingerprintKeys: MutableSet<String> = linkedSetOf()
    }

    private val episodes = mutableListOf<EpisodeRow>()
    private val memberKeys = linkedSetOf<String>()
    private val memberRows = mutableListOf<MemberRow>()
    private var nextEpisodeId = 1L

    override fun findActive(partition: EpisodePartition): EpisodeSnapshot? =
        episodes
            .filter { it.partition == partition }
            .maxByOrNull(EpisodeRow::generation)
            ?.toSnapshot()

    override fun createEpisode(spec: NewEpisodeSpec): Long {
        val id = nextEpisodeId++
        episodes += EpisodeRow(
            id = id,
            partition = spec.partition,
            generation = spec.generation,
            identityLevel = spec.identityLevel,
            errorSignature = spec.errorSignature,
            sessionState = spec.sessionState,
            sessionOpenedAt = spec.sessionOpenedAt,
            sessionLastEventAt = spec.sessionLastEventAt,
            impact = spec.impact,
            memberRevision = spec.memberRevision,
            policyVersion = spec.policyVersion
        )
        return id
    }

    override fun appendMember(append: EpisodeMemberAppend): MemberAppendOutcome {
        val row = episodes.firstOrNull { it.id == append.episodeId }
            ?: error("Episode ${append.episodeId} 不存在：唯一键与事实表在此处不一致")
        // 唯一键 uk_episode_member_revision 的等价物。用「已存在的键」判定而不是先查后插，
        // 是因为真实实现里唯一键是**唯一**能同时挡住串行重投与并发重投的东西。
        if (!memberKeys.add("${append.episodeId}/${append.sourceId}/${append.membershipRevision}")) {
            return MemberAppendOutcome.ALREADY_PRESENT
        }

        memberRows += MemberRow(
            episodeId = append.episodeId,
            sourceId = append.sourceId,
            membershipRevision = append.membershipRevision,
            joinReason = append.joinReason,
            duplicateEndpoint = append.duplicateEndpoint,
            eventTime = append.eventTime
        )
        val sourceFacts = facts.factsOf(append.sourceId)
        sourceFacts.endpointKey?.let(row.endpointKeys::add)
        sourceFacts.fingerprints.forEach { fact ->
            when (fact.type) {
                MemberFactTypes.DEVICE -> row.deviceFingerprintKeys += fact.fingerprint.toHex()
                in MemberFactTypes.USER -> row.userFingerprintKeys += fact.fingerprint.toIdentityKey()
                // 其它类型不参与任何去重集合：宁可少计，也不能把不属于这两个集合的指纹塞进去，
                // 让设备数或用户数凭空变大。
                else -> Unit
            }
        }
        return MemberAppendOutcome.INSERTED
    }

    override fun updateEpisode(episodeId: Long, update: EpisodeUpdate) {
        val row = episodes.firstOrNull { it.id == episodeId }
            ?: error("Episode $episodeId 不存在，无法更新")
        row.impact = update.impact
        row.sessionState = update.sessionState
        row.sessionLastEventAt = update.sessionLastEventAt
        row.memberRevision = update.memberRevision
        update.fillErrorSignature?.let { row.errorSignature = it }
    }

    // -----------------------------------------------------------------------
    // 以下访问器只给套件用：断言「落到哪个问题对象、成员以什么原因并入」需要看到内部行。
    // 它们刻意**不属于** [EpisodeSessionStore]：端口开大之后，会话化之外的地方
    // 也会顺手用它改 Episode 行，而计数与修订号一旦被别处改过，
    // 「影响面按去重后计」这个口径就再也无法从代码上看出来了。
    // -----------------------------------------------------------------------

    fun episodeCount(): Int = episodes.size

    fun episodeIds(): List<Long> = episodes.map(EpisodeRow::id)

    fun members(episodeId: Long): List<MemberRow> = memberRows.filter { it.episodeId == episodeId }

    /** 取某个问题对象的当前快照；用于断言「经过若干次并入之后计数是多少」。 */
    fun snapshot(episodeId: Long): EpisodeSnapshot? =
        episodes.firstOrNull { it.id == episodeId }?.toSnapshot()

    /**
     * 行 → 快照的唯一转换点。
     *
     * 刻意不复用 [findActive]：那个方法按分区取**最大代**，而这里要的是指定 id 的那一代。
     * 混用会让「查第 1 代的计数」在已经有第 2 代时返回第 2 代的数字，且看起来一切正常。
     */
    private fun EpisodeRow.toSnapshot(): EpisodeSnapshot = EpisodeSnapshot(
        episodeId = id,
        generation = generation,
        identityLevel = identityLevel,
        identityFingerprintKey = partition.identityFingerprint.toIdentityKey(),
        errorSignature = errorSignature,
        sessionState = sessionState,
        sessionOpenedAt = sessionOpenedAt,
        sessionLastEventAt = sessionLastEventAt,
        memberRevision = memberRevision,
        impact = impact,
        endpointKeys = endpointKeys.toSet(),
        deviceFingerprints = deviceFingerprintKeys.toSet(),
        identityFingerprints = userFingerprintKeys.toSet()
    )
}

/**
 * [WorkItemDispatchPort] 的内存实现。
 *
 * 唯一键 `uk_work_item_episode` 与 `uk_rca_agent_task_key` 都按冲突即命中的方式实现：
 * 建不出来就返回已有标识并置 `created = false`，而不是抛异常——
 * 重复投递在接入链路上是正常事件，不是错误。
 */
class InMemoryWorkItemDispatch : WorkItemDispatchPort {

    /** 工作项快照，供套件断言使用。 */
    data class WorkItemRow(
        val id: Long,
        val episodeId: Long,
        var status: String,
        var firstSeenAt: LocalDateTime,
        var lastSeenAt: LocalDateTime,
        var reopenCount: Int
    )

    private val workItems = mutableListOf<WorkItemRow>()
    private val taskKeys = linkedSetOf<String>()
    private var nextWorkItemId = 1L

    override fun ensureWorkItem(
        episodeId: Long,
        firstSeenAt: LocalDateTime,
        lastSeenAt: LocalDateTime
    ): WorkItemOutcome {
        workItems.firstOrNull { it.episodeId == episodeId }?.let {
            return WorkItemOutcome(it.id, created = false)
        }
        val row = WorkItemRow(
            id = nextWorkItemId++,
            episodeId = episodeId,
            // 与 t_feedback_work_item 的默认值一致：建出来就是 OPEN，认领后才是 INVESTIGATING。
            status = "OPEN",
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            reopenCount = 0
        )
        workItems += row
        return WorkItemOutcome(row.id, created = true)
    }

    override fun touchWorkItem(episodeId: Long, lastSeenAt: LocalDateTime, reopened: Boolean) {
        val row = workItems.firstOrNull { it.episodeId == episodeId } ?: return
        // 末次出现时间只前进：与 chk_work_item_time_order 一起保证 last_seen_at >= first_seen_at，
        // 也让「重开窗口」不会被一条迟到事件往回拨。
        if (lastSeenAt.isAfter(row.lastSeenAt)) row.lastSeenAt = lastSeenAt
        if (reopened) {
            row.reopenCount += 1
            row.status = "REOPENED"
        }
    }

    override fun enqueueRcaTask(episodeId: Long, taskKey: String, clusterId: Long?): TaskEnqueueOutcome =
        TaskEnqueueOutcome(taskKey = taskKey, created = taskKeys.add(taskKey))

    fun workItem(episodeId: Long): WorkItemRow? = workItems.firstOrNull { it.episodeId == episodeId }

    fun taskCount(): Int = taskKeys.size
}
