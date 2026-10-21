package com.feedback.agent.rca.episode

import com.feedback.mapper.rca.EpisodeSessionMapper
import com.feedback.model.po.rca.EpisodeMemberInsertPo
import com.feedback.model.po.rca.EpisodeSessionInsertPo
import com.feedback.model.po.rca.WorkItemInsertPo
import com.feedback.agent.rca.domain.EpisodeSessionState
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.TrustedIdentityLevel
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * [EpisodeSessionStore] 与 [WorkItemDispatchPort] 的 MyBatis 实现。
 *
 * 一个适配器实现两个端口，是因为它们背后是**同一次接入**的四张表；
 * 端口分开是为了表达「失败代价不同」，不是为了拆成两个 Bean。
 *
 * ## 唯一键冲突是领域结果，不是异常
 *
 * 接入链路是至少一次投递，重复投递是正常事件。三处冲突各自翻译成一个领域结果，
 * 调用方因此不需要写 try/catch，也不需要「先查一次再决定」：
 *
 * | 冲突 | 唯一键 | 翻译成 |
 * |---|---|---|
 * | 重复追加成员 | `uk_episode_member_revision` | [MemberAppendOutcome.ALREADY_PRESENT] |
 * | 重复建工作项 | `uk_work_item_episode` | `WorkItemOutcome.created = false` |
 * | 重复投递任务 | `uk_rca_agent_task_key` | `TaskEnqueueOutcome.created = false` |
 *
 * 捕获的是 [DuplicateKeyException]（MySQL 1062）而**不是** `DataIntegrityViolationException`：
 * 后者同时覆盖外键与 CHECK 约束失败，把「这条 source 不存在」也翻译成「已经在了」，
 * 于是成员行没插进去、计数却照常推进——一个假的影响面数字，且没有任何痕迹。
 *
 * 事务由调用方（[EpisodeIntakeService.accept]）界定。在事务内捕获唯一键冲突不污染事务：
 * MySQL 的重复键错误只回滚该语句，不中止整个事务。这一点与 PostgreSQL 相反，
 * 换库时必须重看这里。
 */
@Component
class EpisodeSessionAdapter(
    private val mapper: EpisodeSessionMapper
) : EpisodeSessionStore, WorkItemDispatchPort {

    /**
     * 读活跃代 + 它的三个去重集合，共三条件语句。
     *
     * 多出来的两次 join 不是随手加的：去重集合从事实表推导（见 [findMemberFacts] 的说明），
     * 而会话化判定需要它们作为纯函数的输入。一次判定多两个往返，
     * 换来的是「影响面按去重后计」这个数字永远可以从库里复算出来。
     */
    override fun findActive(partition: EpisodePartition): EpisodeSnapshot? {
        val row = mapper.findActiveEpisode(
            tenantKey = partition.tenantKey,
            appId = partition.appId,
            problemKey = partition.problemKey,
            identityFingerprint = partition.identityFingerprint
        ) ?: return null

        val facts = mapper.findMemberFacts(row.id)
        return EpisodeSnapshot(
            episodeId = row.id,
            generation = row.generation,
            identityLevel = TrustedIdentityLevel.fromStorage(row.identityLevel)
                ?: error(
                    "Episode ${row.id} 的 identity_level='${row.identityLevel}' 不是已知等级，属数据不一致"
                ),
            // 指纹来自查询条件（分区键）而不是行：行上那一列就是它，回读只会引入
            // 「读到的和查的可能是两个值」这种自找的歧义。
            identityFingerprintKey = partition.identityFingerprint.toIdentityKey(),
            errorSignature = row.errorSignature,
            sessionState = EpisodeSessionState.fromStorage(row.sessionState)
                ?: error(
                    "Episode ${row.id} 的 session_state='${row.sessionState}' 不是已知状态，属数据不一致"
                ),
            sessionOpenedAt = row.sessionOpenedAt.required(row.id, "session_opened_at"),
            sessionLastEventAt = row.sessionLastEventAt.required(row.id, "session_last_event_at"),
            memberRevision = row.memberRevision,
            impact = ImpactCounts(
                repeatCount = row.repeatCount,
                distinctUserCount = row.distinctUserCount,
                distinctDeviceCount = row.distinctDeviceCount
            ),
            endpointKeys = mapper.findMemberEndpointKeys(row.id).toSet(),
            deviceFingerprints = facts
                .filter { it.identityType == MemberFactTypes.DEVICE }
                .map { it.fingerprint.toHex() }
                .toSet(),
            // 与 EpisodeSignal.identityKey() 同一编码（见 toIdentityKey 的说明）：
            // 这里若换了写法，「已见过的身份」会多出条目，用户数随之偏大且不报错。
            identityFingerprints = facts
                .filter { it.identityType in MemberFactTypes.USER }
                .map { it.fingerprint.toIdentityKey() }
                .toSet()
        )
    }

    override fun createEpisode(spec: NewEpisodeSpec): Long {
        val row = EpisodeSessionInsertPo(
            publicId = UUID.randomUUID().toString(),
            tenantKey = spec.partition.tenantKey,
            appId = spec.partition.appId,
            problemKey = spec.partition.problemKey,
            identityFingerprint = spec.partition.identityFingerprint,
            generation = spec.generation,
            identityLevel = spec.identityLevel.storageCode,
            errorSignature = spec.errorSignature,
            sessionState = spec.sessionState.storageCode,
            sessionOpenedAt = spec.sessionOpenedAt,
            sessionLastEventAt = spec.sessionLastEventAt,
            repeatCount = spec.impact.repeatCount,
            distinctUserCount = spec.impact.distinctUserCount,
            distinctDeviceCount = spec.impact.distinctDeviceCount,
            memberRevision = spec.memberRevision,
            policyVersion = spec.policyVersion
        )
        mapper.insertEpisode(row)
        // 主键由数据库自增后回填（useGeneratedKeys）。取 0 说明回填没生效，
        // 那时后续的成员与外键都会指向一个不存在的 Episode，必须在这里就炸。
        return row.id.takeIf { it > 0 }
            ?: error("插入 Episode 后未取回主键，说明 useGeneratedKeys 未生效")
    }

    override fun appendMember(append: EpisodeMemberAppend): MemberAppendOutcome =
        try {
            mapper.insertMember(
                EpisodeMemberInsertPo(
                    episodeId = append.episodeId,
                    sourceId = append.sourceId,
                    membershipRevision = append.membershipRevision,
                    identityLevel = append.identityLevel.storageCode,
                    joinReason = append.joinReason,
                    eventTime = append.eventTime,
                    duplicateEndpoint = append.duplicateEndpoint
                )
            )
            MemberAppendOutcome.INSERTED
        } catch (_: DuplicateKeyException) {
            MemberAppendOutcome.ALREADY_PRESENT
        }

    override fun updateEpisode(episodeId: Long, update: EpisodeUpdate) {
        val affected = mapper.updateEpisode(
            episodeId = episodeId,
            sessionState = update.sessionState.storageCode,
            sessionLastEventAt = update.sessionLastEventAt,
            repeatCount = update.impact.repeatCount,
            distinctUserCount = update.impact.distinctUserCount,
            distinctDeviceCount = update.impact.distinctDeviceCount,
            memberRevision = update.memberRevision,
            fillErrorSignature = update.fillErrorSignature
        )
        // 与租约写回不同，这里影响行数为 0 不是「被别人抢先」而是**行不存在**：
        // 更新一条主键确定的已有行，0 行只可能是 id 错了。
        check(affected == 1) { "更新 Episode $episodeId 影响 $affected 行，该行不存在或主键有误" }
    }

    // -----------------------------------------------------------------------
    // WorkItemDispatchPort
    // -----------------------------------------------------------------------

    /**
     * 建工作项或取回已有的那个，一条语句完成。
     *
     * `created` 由影响行数判定（1 = 本次插入，0 = 已存在）。两条路径都从 `row.id` 取标识，
     * 所以不存在「成功路径与冲突路径各自解析一次标识」而分叉的可能——
     * 那种分叉的表现是工作项串到了别的 Episode 上，只有翻数据才能发现。
     */
    override fun ensureWorkItem(
        episodeId: Long,
        firstSeenAt: LocalDateTime,
        lastSeenAt: LocalDateTime
    ): WorkItemOutcome {
        val row = WorkItemInsertPo(
            publicId = UUID.randomUUID().toString(),
            episodeId = episodeId,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt
        )
        val affected = mapper.upsertWorkItem(row)
        check(affected == 1 || affected == 0) {
            "工作项 upsert 影响 $affected 行，超出「插入(1) / 已存在(0)」两种预期"
        }
        // 回填后仍为 0，说明 useGeneratedKeys 与 LAST_INSERT_ID(id) 没有生效：
        // 那时返回的标识是 0，调用方会把一个不存在的 id 当成工作项。
        val workItemId = row.id.takeIf { it > 0 }
            ?: error(
                "Episode $episodeId 的工作项 upsert 后未取回主键，说明 LAST_INSERT_ID 回填未生效"
            )
        return WorkItemOutcome(workItemId, created = affected == 1)
    }

    override fun touchWorkItem(episodeId: Long, lastSeenAt: LocalDateTime, reopened: Boolean) {
        val affected = mapper.touchWorkItem(episodeId, lastSeenAt, reopened)
        // 并入路径上工作项必然已存在（建问题对象时就建了）。0 行说明这个不变式被破坏了，
        // 静默返回会让「重开次数没涨」这种事在很久以后才以「统计不对」的形式暴露出来。
        check(affected == 1) { "触达 Episode $episodeId 的工作项影响 $affected 行，该工作项不存在" }
    }

    override fun enqueueRcaTask(episodeId: Long, taskKey: String, clusterId: Long?): TaskEnqueueOutcome {
        val created = try {
            mapper.insertRcaTask(
                publicId = UUID.randomUUID().toString(),
                taskKey = taskKey,
                episodeId = episodeId,
                clusterId = clusterId
            )
            true
        } catch (_: DuplicateKeyException) {
            false
        }
        return TaskEnqueueOutcome(taskKey, created)
    }
}

/**
 * 取一个 NOT NULL 的列。
 *
 * 空值只可能来自「查询与表结构不一致」，而默认成 [LocalDateTime.MIN] 会让它继续往下走：
 * 会话边界与不活跃判定的所有比较都会得出「很久以前」的结论，
 * 于是一批本来该并入的信号被判定成各自开新代，且没有任何一处会报错。
 */
private fun LocalDateTime?.required(episodeId: Long, column: String): LocalDateTime =
    this ?: error("Episode $episodeId 的 $column 为空，该列是 NOT NULL，属查询与表结构不一致")
