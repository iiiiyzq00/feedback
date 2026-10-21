package com.feedback.agent.rca.runtime

import com.feedback.common.SafeErrorDetail
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.graph.RcaStateGraph

/**
 * 一次 `runOnce` 的结果。
 *
 * 四种归宿刻意分开而不是压缩成「成功/失败」：它们的**后续动作完全不同**——
 * [Idle] / [LostRace] 什么都不做，[Finished] 什么都不做，
 * [Discarded] 要触发账号级告警（结果被丢弃说明有并发接管），
 * [PendingRetry] / [Abandoned] 才需要人工或调度介入。
 */
sealed interface RcaWorkerRun {

    /** 队列里没有可领取的任务。空转是正常状态，不该产生告警或指标噪声。 */
    data object Idle : RcaWorkerRun

    /**
     * 取到候选但条件领取被并发抢占。
     *
     * 与 [Idle] 分开统计：前者说明没活干，后者说明有争抢。
     * 合成一个会让「领取成功率」被空队列掩盖。
     */
    data class LostRace(val taskId: Long) : RcaWorkerRun

    /** 跑完并成功写回。此时任务要么已终态，要么停在 [nextNode] 上等下一次领取。 */
    data class Finished(
        val taskId: Long,
        val status: RcaTaskStatus,
        /** 落库后的状态版本，恒为写回前令牌版本 + 1（见 [RcaTaskLeasePort.writeBack]）。 */
        val stateVersion: Long,
        val nextNode: RcaNode?
    ) : RcaWorkerRun

    /** 结果被整体丢弃：租约在中途丢失，或终态写回被 fencing 拒绝。 */
    data class Discarded(val taskId: Long, val reason: RcaDiscardReason) : RcaWorkerRun

    /** 执行中抛异常，任务已让出等待下一次领取。 */
    data class PendingRetry(
        val taskId: Long,
        val errorCode: String,
        /** 让出本身是否生效；为 [RcaWriteBackResult.FENCED_OUT] 说明任务已易主，本 Worker 不必再管。 */
        val writeBack: RcaWriteBackResult
    ) : RcaWorkerRun

    /** 无法继续也无法重试，已收口成非正常终态。 */
    data class Abandoned(
        val taskId: Long,
        val status: RcaTaskStatus,
        val stopReason: RcaStopReason,
        val errorCode: String,
        val writeBack: RcaWriteBackResult
    ) : RcaWorkerRun
}

/** 结果被丢弃的原因。两类都不需要本 Worker 做任何补救动作。 */
enum class RcaDiscardReason(val storageCode: String) {

    /** 节点边界续租失败：租约已被接管，任务很可能正在别处继续跑。 */
    LEASE_LOST("LEASE_LOST"),

    /** 终态写回影响行数为 0：状态已被别人推进。 */
    FENCED_OUT("FENCED_OUT"),

    /** 快照无法反序列化，恢复信息已不完整。 */
    SNAPSHOT_UNREADABLE("SNAPSHOT_UNREADABLE")
}

/**
 * 单个 RCA 任务的执行单元。
 *
 * 生命周期只有一条主线，没有分支状态机：
 *
 * ```
 * 领取 → 超期？ → 恢复判定 → （节点边界：续租 + 写回）× n → 终态写回
 *         ↓ 是                  ↓ 有判定不了的调用   ↓ 续租失败或写回被拒
 *       收口（不进图）           转人工              整体丢弃
 * ```
 *
 * 五个设计取舍值得说明：
 *
 * **1. 中断点只放在节点边界。** 只有节点边界上的状态是自洽的（没有「工具已调用但结果未入账」
 * 这种半成品），所以只有那里可以安全落库并让出。节点内部中断会把状态留在中间态，
 * 恢复时无法区分「没调用」和「调用了没记账」——这正是三态对账要解决的问题，
 * 而避免它比修复它更省事。因此 Worker 用 [RcaStateGraph.run] 的边界钩子，
 * 而不是自己在外层循环调用节点：外层循环会把路由规则复制一份出来。
 *
 * **2. 边界上的具体动作交给 [RcaBoundaryWriter]。** 续租与写回的先后顺序、令牌如何前进、
 * 首个边界为什么不写回，都是那一个类的职责。Worker 只负责「什么时候开始、什么时候丢弃」。
 * 分开还有一个直接好处：故障注入可以复用同一段边界逻辑去模拟崩溃，
 * 而不是自己另写一份——那样验证的是复制品。
 *
 * **3. 写回被拒绝不是异常，是丢弃信号。** 影响行数为 0 意味着租约或状态已被接管，
 * 此时整个结果都作废：重试只会再失败一次，而把结果「部分写入」会让新持有者读到
 * 一份自相矛盾的状态。所以这条路径上不做任何补救动作，只留痕。
 *
 * **4. 进图之前先做恢复判定。** 上一次执行可能留下「已发出但未落定」的调用，
 * 它既不能当成没发生（上游可能已经执行），也不能当成已完成。
 * 这一层只做拦截：判定不了就转人工，判定为重放则交给节点去重放——
 * 在这里补一次调用会让节点随后再发一次，两次调用一条证据，事后无法分辨。
 *
 * **5. 超期的任务在进图之前收口，且排在恢复判定之前。** 理由见 [abandonOverdue]：
 * 超期否定的是「这次执行还有预算」这个前提，而恢复判定同样以它为前提。
 * 顺序反过来会变成「先花掉几步去判断能不能重放，然后才发现不该开始」——
 * 现在这几步只是查表，但它们是会长的（对账口就是一个还没接的网络调用）。
 *
 * Worker 不持有任何业务判断：预算、停止条件、归因等级都由节点决定。
 * 它只负责「什么时候可以安全地落库、落库失败之后怎么办、以及能不能开始」。
 */
class RcaWorker(
    private val leases: RcaTaskLeasePort,
    private val graph: RcaStateGraph,
    private val codec: RcaTaskSnapshotCodec,
    private val clock: RcaClock,
    /**
     * 持有者标识。
     *
     * 允许在一次运行内稳定复用（例如「主机名 + 线程名」），**这不是漏洞而是刻意的**：
     * fencing 靠的是 [RcaLeaseToken.generation]，不靠 owner 的随机性。
     * 若把唯一性压在 owner 上，就等于把「防重放」的责任交给了一个会被复用的字符串——
     * 进程重启后同名、容器同名都会让旧写回重新生效（ABA）。
     */
    private val workerId: String,
    /** 单次租约时长。必须大于最慢节点的执行时间，否则会出现「干到一半租约没了」。 */
    private val leaseSeconds: Long = DEFAULT_LEASE_SECONDS,
    /**
     * 恢复判定入口。
     *
     * 默认是「放行」，但这只对没有调用记录表的部署成立；
     * 接了记录表就必须注入真实实现，否则三态对账不会被触发（见 [RcaRecoveryResolver]）。
     */
    private val recovery: RcaRecoveryResolver = RcaRecoveryResolver.NONE
) {

    init {
        require(workerId.isNotBlank()) { "Worker 标识不能为空：空标识会让 fencing 退化成 owner 匹配" }
        require(leaseSeconds > 0) { "租约时长必须为正数，当前为 $leaseSeconds" }
    }

    /** 领取并执行一个任务；没有可领取的任务就空转返回。 */
    fun runOnce(): RcaWorkerRun {
        val claim = when (val outcome = leases.claim(workerId, leaseSeconds)) {
            RcaClaimOutcome.NoCandidate -> return RcaWorkerRun.Idle
            is RcaClaimOutcome.LostRace -> return RcaWorkerRun.LostRace(outcome.taskId)
            is RcaClaimOutcome.Claimed -> outcome
        }
        val task = claim.task

        // 超期判定必须在**其他一切之前**：解密快照、恢复判定、进图，都以「这次执行还有预算」
        // 为前提，而超期恰恰否定的就是这个前提。放在后面会让一个没有预算的任务照样走完
        // 上面几步——那些步骤不是免费的（恢复判定会去查上游对账口，将来还会是一次网络调用）。
        // 判据取自库里算出的差值，不取 Worker 的墙钟：超期与否必须与「能不能被领取」
        // 用同一个时钟裁定，否则会出现「库里已超期、Worker 认为还有 5 分钟」这种分歧。
        val deadlineRemainingMs = task.deadlineRemainingMs
        if (deadlineRemainingMs != null && deadlineRemainingMs <= 0) {
            return abandonOverdue(claim.token, task, deadlineRemainingMs)
        }

        // 快照读不出来时**不能**按空状态继续跑：那会产出一份「看起来完整、实际丢了全部证据」的
        // 结论，比直接失败危险得多。这种任务重试也必然失败（同一份坏快照），所以直接收口。
        val state = try {
            task.stateSnapshot?.let(codec::decode)
                ?: RcaSharedState.seeded(task.taskId, task.episodeId, clock.now())
        } catch (ex: Exception) {
            return abandonSnapshotUnreadable(claim.token, task.taskId, ex)
        }

        // 恢复判定必须在进图之前：进了图就会重新取证、重新计费、重新引用那条判定不了的记录，
        // 而这一切都发生在「能不能继续」这个问题被回答之前。
        when (val outcome = recovery.resolve(task.taskId, state)) {
            RcaRecoveryOutcome.Proceed -> Unit
            is RcaRecoveryOutcome.Blocked -> return abandonUnresolvable(claim.token, task.taskId, outcome)
        }

        val boundary = RcaBoundaryWriter(leases, codec, workerId, leaseSeconds, claim.token)
        val result = try {
            // traceId 从**任务行**取，不在 Worker 侧生成：标识唯一要保证的是
            // 「同一件事在所有地方是同一个值」，而任务可能被别的 Worker 接管续跑，
            // 各自生成就会把一次执行劈成两段互不相干的链路。领取时已由 claim 补齐。
            graph.run(state, task.currentNode, boundary::onBoundary, traceId = task.traceId)
        } catch (ex: Exception) {
            return handBack(boundary.token, task, ex)
        }

        // 被边界钩子中止：本次结果整体丢弃，连终态写回都不做——
        // 租约已不在本 Worker 手里，任务很可能正在另一个 Worker 手上继续演进。
        if (!result.conclusive) {
            return RcaWorkerRun.Discarded(task.taskId, RcaDiscardReason.LEASE_LOST)
        }

        return when (val written = leases.writeBack(
            boundary.token,
            mutation(null, result.status, result.stopReason, result.finalState)
        )) {
            RcaWriteBackResult.APPLIED -> RcaWorkerRun.Finished(
                taskId = task.taskId,
                status = result.status,
                stateVersion = boundary.token.stateVersion + 1,
                nextNode = null
            )
            RcaWriteBackResult.FENCED_OUT -> RcaWorkerRun.Discarded(task.taskId, RcaDiscardReason.FENCED_OUT)
        }
    }

    /**
     * 异常退出：让出任务等待重试。
     *
     * 但**最后一次尝试不能走这条路**：`claim` 的条件里有 `attempt_count < max_attempts`，
     * 让出之后这条任务将不再被任何 Worker 选中，于是一个必然失败的任务会永远停在
     * `RETRY_PENDING` 上——既不会被处理，也不会出现在任何「待处理」看板里。
     * 因此 attempt 用尽时必须显式收口成 `NEEDS_HUMAN`。
     */
    private fun handBack(token: RcaLeaseToken, task: RcaClaimedTask, ex: Exception): RcaWorkerRun {
        val errorCode = SafeErrorDetail.code(ex)
        // 只落错误类型与阶段，不落异常消息：消息里可能带上游响应片段或用户标识，
        // 而 error_detail 是会被人工在后台直接看到的字段。
        val errorDetail = SafeErrorDetail.summary(ex, STAGE)

        if (task.attemptCount >= task.maxAttempts) {
            val written = leases.abandon(
                token = token,
                status = RcaTaskStatus.NEEDS_HUMAN,
                stopReason = RcaStopReason.HUMAN_REQUIRED,
                errorCode = errorCode,
                errorDetail = errorDetail
            )
            return RcaWorkerRun.Abandoned(
                task.taskId,
                RcaTaskStatus.NEEDS_HUMAN,
                RcaStopReason.HUMAN_REQUIRED,
                errorCode,
                written
            )
        }

        val written = leases.releaseForRetry(token, errorCode, errorDetail)
        return RcaWorkerRun.PendingRetry(task.taskId, errorCode, written)
    }

    /**
     * 领取时任务已超期：不再执行，直接收口。
     *
     * ## 为什么是「收口」而不是「让给别人等它过期」
     *
     * 超期任务的候选条件里没有 `deadline_at`（见 XML 与 `RcaTaskLeaseMapper`），
     * 所以它会被选中——选中它正是为了在这一刻把它结束掉。不收口的话，
     * 「超期」只是让它永远不被领取，任务会停在 `RETRY_PENDING` 上：
     * 既不是终态、也没有执行者、也不出现在任何「待处理」看板里。
     *
     * ## 状态按「有没有留下快照」分两种，理由与图内那条规定同源
     *
     * [com.feedback.agent.rca.graph.AgentInvestigateNode] 里 `DEADLINE_EXCEEDED` 判 `PARTIAL`
     * （「预算/步数耗尽属于覆盖不足，保留已有证据」）。这里沿用同一条：
     * 有快照说明上一轮真的取过证，那些证据还在，`PARTIAL`（有可用证据但覆盖不足）是准确的。
     * 没有快照则一条证据都没有，`PARTIAL` 会变成一句假话——此时只能是 `NEEDS_HUMAN`。
     *
     * 注意 `abandon` **不动 `state_snapshot`**：上一轮写下的证据因此不会因为这次收口而消失，
     * 「保留已有证据」这句话才成立。
     */
    private fun abandonOverdue(
        token: RcaLeaseToken,
        task: RcaClaimedTask,
        deadlineRemainingMs: Long
    ): RcaWorkerRun {
        val hasEvidenceSnapshot = task.stateSnapshot != null
        val status = if (hasEvidenceSnapshot) RcaTaskStatus.PARTIAL else RcaTaskStatus.NEEDS_HUMAN
        val written = leases.abandon(
            token = token,
            status = status,
            stopReason = RcaStopReason.DEADLINE_EXCEEDED,
            errorCode = DEADLINE_EXCEEDED_CODE,
            // 只说「超期了、差多少」，不复述配置里的分钟数：Worker 看不到那个配置，
            // 写一个推测出来的数字会让事后核对的人以为它是权威值。
            errorDetail = if (hasEvidenceSnapshot) {
                "领取时任务已超过截止时间 $deadlineRemainingMs 毫秒（负数即超期），本次不执行，" +
                    "按超期收口并保留上一轮已落库的证据快照"
            } else {
                "领取时任务已超过截止时间 $deadlineRemainingMs 毫秒（负数即超期），且库上没有状态快照，" +
                    "本次未执行任何节点"
            }
        )
        return RcaWorkerRun.Abandoned(
            taskId = task.taskId,
            status = status,
            stopReason = RcaStopReason.DEADLINE_EXCEEDED,
            errorCode = DEADLINE_EXCEEDED_CODE,
            writeBack = written
        )
    }

    /**
     * 存在判定不了的调用记录：收口成 `NEEDS_HUMAN`。
     *
     * 这里**不走重试**：判定不了的原因（上游查不到最终状态、本地记账为 UNKNOWN）
     * 不会因为再跑一次而变得可判定，重试只会让同一个问题再铺一次日志，
     * 并把任务在多轮尝试里来回搬运。
     */
    private fun abandonUnresolvable(
        token: RcaLeaseToken,
        taskId: Long,
        outcome: RcaRecoveryOutcome.Blocked
    ): RcaWorkerRun {
        val errorCode = RECOVERY_UNRESOLVED_CODE
        val written = leases.abandon(
            token = token,
            status = RcaTaskStatus.NEEDS_HUMAN,
            stopReason = RcaStopReason.HUMAN_REQUIRED,
            errorCode = errorCode,
            // 判定理由必须落库：同一类故障在两台 Worker 上得到不同处置时，只有它能说明差异从哪来。
            errorDetail = outcome.reason.take(DETAIL_MAX_LENGTH)
        )
        return RcaWorkerRun.Abandoned(
            taskId = taskId,
            status = RcaTaskStatus.NEEDS_HUMAN,
            stopReason = RcaStopReason.HUMAN_REQUIRED,
            errorCode = errorCode,
            // 如实回传：为 0 行说明租约已被接管，此时「已转人工」这句话并不成立，
            // 把它写成 APPLIED 会让人以为任务已经收口，而它其实还在别人手上跑。
            writeBack = written
        )
    }

    /** 快照损坏：重试必然复现，直接收口成失败并留下错误码供人工定位。 */
    private fun abandonSnapshotUnreadable(token: RcaLeaseToken, taskId: Long, ex: Exception): RcaWorkerRun {
        val errorCode = SafeErrorDetail.code(ex)
        leases.abandon(
            token = token,
            status = RcaTaskStatus.FAILED,
            stopReason = RcaStopReason.HUMAN_REQUIRED,
            errorCode = errorCode,
            errorDetail = SafeErrorDetail.summary(ex, SNAPSHOT_STAGE)
        )
        return RcaWorkerRun.Discarded(taskId, RcaDiscardReason.SNAPSHOT_UNREADABLE)
    }

    /**
     * 构造一次写回内容。
     *
     * 预算三项都从状态里取而不是从数据库读回：状态的预算账本才是唯一权威，
     * 数据库里那三列只是它的投影。从库里取会让「节点内部已经消耗但尚未落库」的部分丢失，
     * 从而在恢复后重复扣减。
     */
    private fun mutation(
        nextNode: RcaNode?,
        taskStatus: RcaTaskStatus,
        stopReason: RcaStopReason?,
        state: RcaSharedState
    ): RcaTaskMutation {
        val snapshot = codec.encode(state)
        val budget = state.gapsAndBudget
        return RcaTaskMutation(
            nextNode = nextNode,
            taskStatus = taskStatus,
            stopReason = stopReason,
            stateSnapshot = snapshot.json,
            snapshotSha256 = snapshot.sha256,
            stepsUsed = budget.stepsUsed,
            tokensUsed = budget.tokensUsed,
            costUsedUsd = budget.costUsedUsd
        )
    }

    private companion object {
        /**
         * 默认租约时长。
         *
         * 取值依据是「模型单轮决策 + 一次工具调用」的量级：模型侧超时通常按 30s 设，
         * 工具侧按 10s，再加上多次重试，60s 覆盖了单节点的正常执行时间并留有余量。
         * 调小会让正常执行被判成租约丢失，调大则会让一个崩溃的 Worker 占住任务更久。
         */
        const val DEFAULT_LEASE_SECONDS = 60L

        const val STAGE = "RCA_WORKER"
        const val SNAPSHOT_STAGE = "RCA_SNAPSHOT_DECODE"

        /**
         * 恢复判定不通过的错误码。
         *
         * 与「执行中抛异常」共用 `t_rca_agent_task.error_code` 一列，所以取值必须能自证来源：
         * 看到的是一般异常码还是恢复判定失败，决定了人工是去看堆栈还是去看调用记录。
         */
        const val RECOVERY_UNRESOLVED_CODE = "RCA_RECOVERY_UNRESOLVED"

        /**
         * 领取时已超期的错误码。
         *
         * 与 `stop_reason='DEADLINE_EXCEEDED'` 同时落库，两者一起才能区分
         * 「图内跑到超时」（停止原因是同一个，但状态是 `PARTIAL` 且有完整的步骤留痕）
         * 与「领到手时就已经超期」（本次一个节点都没进）。
         */
        const val DEADLINE_EXCEEDED_CODE = "RCA_DEADLINE_EXCEEDED"

        /** 与 `t_rca_agent_task.error_detail` 的列宽一致。 */
        const val DETAIL_MAX_LENGTH = 1000
    }
}
