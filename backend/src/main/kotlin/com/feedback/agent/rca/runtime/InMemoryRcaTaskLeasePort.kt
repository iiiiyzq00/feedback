package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

/**
 * 一行任务在某个时刻的完整投影，供故障注入与回放做断言。
 *
 * 独立成只读视图而不是直接暴露可变内部结构：断言的语义是「数据库里现在是什么」，
 * 如果测试拿到的是可写句柄，一次误改就能让后续断言全部失真，而且看不出是谁改的。
 */
data class RcaTaskRow(
    val id: Long,
    val taskKey: String,
    val episodeId: Long,
    val taskStatus: RcaTaskStatus,
    val currentNode: String,
    val stateVersion: Long,
    val leaseOwner: String?,
    /** 已被领取过的次数（每次 claim 递增）。为 0 表示从未被领取。 */
    val leaseGeneration: Long,
    val leaseUntil: LocalDateTime?,
    val stateSnapshot: String?,
    val snapshotSha256: String?,
    val attemptCount: Int,
    val maxAttempts: Int,
    val stepsUsed: Int,
    val tokensUsed: Int,
    val costUsedUsd: BigDecimal,
    val stopReason: RcaStopReason?,
    val errorCode: String?,
    val errorDetail: String?,
    val startedAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    /** 链路追踪标识。仅当 [enqueue] 显式注入过才非空（见该方法的说明）。 */
    val traceId: String? = null
) {
    /** 是否还有持有者。终态必然为 null（写回时清空）。 */
    val leaseHeld: Boolean get() = leaseOwner != null
}

/**
 * [RcaTaskLeasePort] 的内存实现，条件与 `RcaTaskLeaseMapper.xml` **逐条对齐**。
 *
 * 存在的理由只有一个：故障注入需要在「租约过期」「写回被拒」这些点上精确控制时间与并发，
 * 而对着真实的 MySQL 做这件事既慢又不可复现。因此它的价值完全来自「与 SQL 条件一致」，
 * 一旦两边出现分歧，注入测试就会开始为一份不存在的实现背书——所以改 SQL 时**必须**同步改这里，
 * 反之亦然。允许不同的只有两处，都在下面列了出来：时间来源，以及谁写下那些「领取时才补」的列。
 *
 * 它**不是生产实现，也不假装是**：
 * - 没有 `FOR UPDATE SKIP LOCKED`。方法级互斥只能在一个进程内串行化，
 *   而真实的争抢来自多个进程；跨进程的原子性只能由数据库给。
 *   直接后果是 [RcaClaimOutcome.LostRace] 在这里永远不会出现——挑选与写入在同一临界区内，
 *   中间没有可以被别人插进来的窗口。断言里不要依赖它。
 * - 不落任何数据。进程退出即全部消失，因此它不能用于「重启后恢复」这类验证。
 * - **不自动写下那些「领取时才补」的列**：`deadline_at` 与 `trace_id`。
 *   生产侧由 `claim` 用 `IFNULL(deadline_at, NOW(3)+N 分钟)` 与
 *   `IFNULL(trace_id, REPLACE(UUID(),'-',''))` 补上；这里两者都不补。
 *   理由对 `deadline_at` 是「补多久」属于部署配置，而离线套件不读部署配置——真补上会让
 *   每个用例的截止时间取决于一个与用例无关的数字；对 `trace_id` 则是随机性会成为断言的一部分。
 *   取而代之，[enqueue] 的 `deadlineAt` / `traceId` 参数就是注入它们的方式，
 *   与生产侧「领取时补上」的作用相同（差别只在谁给值、以及取值的来源）。
 *
 *   这两处差异都**不可观测**：`deadlineAt` 为 null 的行在任何实现下都不会超期；
 *   `traceId` 为 null 时代码走的是「本次执行不做链路落库」那条分支
 *   （离线套件注入的记录端口是 `RcaToolCallJournalWriter.NONE`，本来就不落库），
 *   于是两个实现下都没有任何链路行被写出来。反过来说：**要验链路落库，
 *   只能用 `:backend:rcaEntryTest` 对着真库跑**，离线套件给不了这个证据。
 */
class InMemoryRcaTaskLeasePort(
    /**
     * **数据库时钟**。租约的先后顺序以它为准，而不是各 Worker 自己的墙钟。
     *
     * 注入测试靠推进它来制造「租约过期」：真实系统里这个时间是 `NOW(3)`，
     * 测试里必须能被人为拨快，否则「等 60 秒再看」会变成测试的一部分。
     */
    private val databaseClock: () -> LocalDateTime = LocalDateTime::now
) : RcaTaskLeasePort {

    private class Row(
        val id: Long,
        val taskKey: String,
        val episodeId: Long,
        var taskStatus: RcaTaskStatus,
        var currentNode: String,
        var stateVersion: Long,
        var leaseOwner: String?,
        var leaseGeneration: Long,
        var leaseUntil: LocalDateTime?,
        var stateSnapshot: String?,
        var snapshotSha256: String?,
        var attemptCount: Int,
        val maxAttempts: Int,
        var deadlineAt: LocalDateTime?,
        var traceId: String?,
        var stepsUsed: Int,
        var tokensUsed: Int,
        var costUsedUsd: BigDecimal,
        var stopReason: RcaStopReason? = null,
        var errorCode: String? = null,
        var errorDetail: String? = null,
        var startedAt: LocalDateTime? = null,
        var completedAt: LocalDateTime? = null
    ) {
        fun toRow() = RcaTaskRow(
            id, taskKey, episodeId, taskStatus, currentNode, stateVersion, leaseOwner, leaseGeneration,
            leaseUntil, stateSnapshot, snapshotSha256, attemptCount, maxAttempts, stepsUsed, tokensUsed,
            costUsedUsd, stopReason, errorCode, errorDetail, startedAt, completedAt, traceId
        )
    }

    private val rows = mutableListOf<Row>()
    private var nextId = 1L

    /**
     * 放一个待处理任务进去，返回任务 ID。等价于插入一条 `PENDING` 行。
     *
     * [deadlineAt] 就是生产侧「领取时补上」的那一列的注入点：给一个**过去**的时刻即可构造
     * 「领取时已超期」的任务（生产上对应「第一次领取后崩了、30 分钟内没人再领」）。
     * 默认 null 表示不设截止时间，此时任何实现都不会判它超期。
     *
     * [traceId] 同理，是生产侧「领取时补上」的链路标识的注入点（见类 KDoc 的最后一条差异）。
     * 取值必须是 32 位小写十六进制，与 `chk_rca_agent_trace` 一致；这里不做格式校验——
     * 内存桩的职责是复现条件而不是复现约束，把 CHECK 也抄一遍会让它开始为一份
     * 与数据库不同的实现背书（真正的约束由 `:backend:rcaEntryTest` 对着真库验）。
     */
    @Synchronized
    fun enqueue(
        taskKey: String,
        episodeId: Long,
        nextNode: RcaNode = RcaNode.CONTEXT_BUILD,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        deadlineAt: LocalDateTime? = null,
        traceId: String? = null
    ): Long {
        val row = Row(
            id = nextId++,
            taskKey = taskKey,
            episodeId = episodeId,
            taskStatus = RcaTaskStatus.PENDING,
            currentNode = nextNode.storageCode,
            stateVersion = 0,
            leaseOwner = null,
            leaseGeneration = 0,
            leaseUntil = null,
            stateSnapshot = null,
            snapshotSha256 = null,
            attemptCount = 0,
            maxAttempts = maxAttempts,
            deadlineAt = deadlineAt,
            traceId = traceId,
            stepsUsed = 0,
            tokensUsed = 0,
            costUsedUsd = BigDecimal.ZERO
        )
        rows += row
        return row.id
    }

    @Synchronized
    fun row(taskId: Long): RcaTaskRow? = rows.firstOrNull { it.id == taskId }?.toRow()

    @Synchronized
    fun rows(): List<RcaTaskRow> = rows.map(Row::toRow)

    override fun claim(owner: String, leaseSeconds: Long): RcaClaimOutcome {
        // 整个「挑候选 → 改行 → 读回」在同一个临界区里完成，对应 SQL 侧的同一事务。
        // 分成两次进入临界区就等于把事务拆开，锁的语义立刻不同了。
        synchronized(this) {
            val now = databaseClock()
            val candidate = rows
                // RUNNING 也要在内：Worker 被杀时行就停在 RUNNING 上，没有让出语句会执行。
                // 只挑 PENDING/RETRY_PENDING 会让这类任务永远无人接管（见 XML 里的同一条说明）。
                .filter {
                    it.taskStatus == RcaTaskStatus.PENDING ||
                        it.taskStatus == RcaTaskStatus.RUNNING ||
                        it.taskStatus == RcaTaskStatus.RETRY_PENDING
                }
                .filter { it.leaseUntil == null || it.leaseUntil!!.isBefore(now) }
                .filter { it.attemptCount < it.maxAttempts }
                // 候选条件里同样**没有** deadline_at：超期任务照样被选中，由 Worker 收口。
                // 排除它会让超期任务永远停在非终态上（见 XML 里的同一条说明）。
                .minByOrNull(Row::id)
                ?: return RcaClaimOutcome.NoCandidate

            candidate.taskStatus = RcaTaskStatus.RUNNING
            candidate.leaseOwner = owner
            candidate.leaseGeneration += 1
            candidate.leaseUntil = now.plusSeconds(leaseSeconds)
            candidate.attemptCount += 1
            if (candidate.startedAt == null) candidate.startedAt = now

            return RcaClaimOutcome.Claimed(
                token = RcaLeaseToken(
                    taskId = candidate.id,
                    owner = owner,
                    generation = candidate.leaseGeneration,
                    stateVersion = candidate.stateVersion,
                    // 与适配器从读回的任务行上取的是同一对值：随行事实，不参与 fencing。
                    // 内存实现里这一步的真实用途是让「事件载荷与令牌一致」这件事
                    // 在离线套件里也能被断言到。
                    episodeId = candidate.episodeId,
                    traceId = candidate.traceId
                ),
                task = RcaClaimedTask(
                    taskId = candidate.id,
                    taskKey = candidate.taskKey,
                    episodeId = candidate.episodeId,
                    currentNode = RcaNode.fromStorage(candidate.currentNode)
                        ?: error("任务 ${candidate.id} 的 current_node=${candidate.currentNode} 不是合法节点"),
                    stateSnapshot = candidate.stateSnapshot,
                    attemptCount = candidate.attemptCount,
                    maxAttempts = candidate.maxAttempts,
                    leaseRemainingMs = remainingMs(candidate, now),
                    // 与 SQL 里 TIMESTAMPDIFF(MICROSECOND,NOW(3),deadline_at)/1000 等价，
                    // 含符号：超期时为负。`Duration.toMillis()` 也是毫秒截断，除法的方向一致。
                    deadlineRemainingMs = deadlineRemainingMs(candidate, now),
                    traceId = candidate.traceId
                )
            )
        }
    }

    override fun renew(token: RcaLeaseToken, leaseSeconds: Long): RcaLeaseRenewal {
        synchronized(this) {
            val now = databaseClock()
            val row = rows.firstOrNull { it.id == token.taskId } ?: return RcaLeaseRenewal.LEASE_LOST
            val matched = row.leaseOwner == token.owner &&
                row.leaseGeneration == token.generation &&
                row.taskStatus == RcaTaskStatus.RUNNING
            if (!matched) return RcaLeaseRenewal.LEASE_LOST
            row.leaseUntil = now.plusSeconds(leaseSeconds)
            return RcaLeaseRenewal.RENEWED
        }
    }

    /** 写回是唯一按 `state_version` 加锁的操作：它基于某一份状态，而不是只基于租约。 */
    override fun writeBack(token: RcaLeaseToken, mutation: RcaTaskMutation): RcaWriteBackResult =
        mutate(token, matchStateVersion = true) { row, now ->
            row.currentNode = mutation.nextNode?.storageCode ?: DONE_NODE
            row.taskStatus = mutation.taskStatus
            row.stopReason = mutation.stopReason
            // 与 SQL 一致：写回成功即推进一版，因此调用方可以据此自行推进令牌而不必回读。
            row.stateVersion += 1
            row.stateSnapshot = mutation.stateSnapshot
            row.snapshotSha256 = mutation.snapshotSha256
            row.stepsUsed = mutation.stepsUsed
            row.tokensUsed = mutation.tokensUsed
            row.costUsedUsd = mutation.costUsedUsd
            if (mutation.taskStatus != RcaTaskStatus.RUNNING) {
                // 终态清空租约：留着持有者会让「谁该负责」有一个过期答案。
                // 非终态保留租约，Worker 接着往下跑。
                row.leaseOwner = null
                row.leaseUntil = null
                row.completedAt = now
            }
        }

    /** 让出与收口只认租约，不认状态版本：它们不基于任何一份状态。 */
    override fun releaseForRetry(token: RcaLeaseToken, errorCode: String, errorDetail: String): RcaWriteBackResult =
        mutate(token, matchStateVersion = false) { row, _ ->
            row.taskStatus = RcaTaskStatus.RETRY_PENDING
            row.leaseOwner = null
            row.leaseUntil = null
            row.errorCode = errorCode
            row.errorDetail = errorDetail
        }

    override fun abandon(
        token: RcaLeaseToken,
        status: RcaTaskStatus,
        stopReason: RcaStopReason,
        errorCode: String,
        errorDetail: String
    ): RcaWriteBackResult = mutate(token, matchStateVersion = false) { row, now ->
        row.taskStatus = status
        row.stopReason = stopReason
        row.stateVersion += 1
        row.leaseOwner = null
        row.leaseUntil = null
        row.errorCode = errorCode
        row.errorDetail = errorDetail
        row.completedAt = now
    }

    /**
     * 除 claim 之外所有写操作的公共骨架。
     *
     * 条件只有一处：`id + owner + generation`，写回再多一个 `stateVersion`
     * （由 [matchStateVersion] 决定）。把「匹配失败返回 FENCED_OUT」集中在这里，
     * 是为了让每条语句的差异只体现在 SET 子句上——分散写容易出现某条语句漏掉 generation，
     * 而漏掉的那条恰恰是事故路径。
     */
    private fun mutate(
        token: RcaLeaseToken,
        matchStateVersion: Boolean,
        apply: (Row, LocalDateTime) -> Unit
    ): RcaWriteBackResult = synchronized(this) {
        val now = databaseClock()
        val row = rows.firstOrNull { it.id == token.taskId } ?: return RcaWriteBackResult.FENCED_OUT
        val matched = row.leaseOwner == token.owner &&
            row.leaseGeneration == token.generation &&
            (!matchStateVersion || row.stateVersion == token.stateVersion)
        if (!matched) return RcaWriteBackResult.FENCED_OUT
        apply(row, now)
        RcaWriteBackResult.APPLIED
    }

    private fun remainingMs(row: Row, now: LocalDateTime): Long {
        val until = row.leaseUntil ?: return 0
        return Duration.between(now, until).toMillis().coerceAtLeast(0)
    }

    /**
     * 与 [remainingMs] 的两处刻意不同，都对应 SQL 那一侧：**不兜底 0**，且**不夹到 0**。
     *
     * 前者：`deadline_at` 为 NULL 时 SQL 的 `TIMESTAMPDIFF` 返回 NULL，读侧也必须是 null——
     * 把它读成 0 会让「从未设过截止时间」变成「刚好到期」。
     * 后者：负数就是「已超期」这个事实本身，夹掉之后 Worker 再也分不出超期与刚好到期。
     */
    private fun deadlineRemainingMs(row: Row, now: LocalDateTime): Long? {
        val deadline = row.deadlineAt ?: return null
        return Duration.between(now, deadline).toMillis()
    }

    private companion object {
        /** `chk_rca_agent_node` 允许的收口标记。 */
        const val DONE_NODE = "DONE"

        /** 与 `t_rca_agent_task.max_attempts` 的默认值一致。 */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
