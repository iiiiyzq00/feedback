package com.feedback.agent.rca.runtime

import com.feedback.mapper.rca.RcaTaskLeaseMapper
import com.feedback.model.po.rca.RcaAgentTaskPo
import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.config.RcaProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [RcaTaskLeasePort] 的 MyBatis 实现。
 *
 * 适配器只做三件事：调语句、看影响行数、把行翻译成领域对象。**不放任何业务判断**——
 * 尤其是「影响行数为 0 该怎么办」这类判断必须留在调用方：
 * 在本层把它翻译成异常，会逼着所有调用点写 try/catch，而正确的处置（丢弃结果、让给别人）
 * 恰恰是那个 catch 里最容易写错的东西。
 *
 * [properties] 只被读取一个值（`task-timeout-minutes`），它是 `claim` 那条语句的参数：
 * 截止时间由**数据库**在领取时刻补上，但时长来自部署配置，所以配置必须能到这里。
 * 为什么不把时长加进 [RcaTaskLeasePort.claim] 的签名：那是「租约」这个领域的端口，
 * 租约时长是它的事，任务超时不是——把它塞进去会让每个调用点都要先知道一个策略值。
 *
 * ## 领域事件为什么在这里发出，而不是在 Worker / 边界写入器里
 *
 * 这是整个事件日志设计里唯一一个必须做对的位置选择。[RcaDomainEventJournal] 的契约要求
 * 「状态迁移已提交」与「事件已提交」同时成立或同时不成立，而**事务边界恰好就在本类**：
 * 下面每个写方法都是 `@Transactional`，`mapper` 的状态语句与 `journal` 的事件写入因此
 * 落在同一个事务里。Worker 与 [RcaBoundaryWriter] 都是框架无关的类，它们连事务都看不见，
 * 在那里发事件只可能发出一个「事后补记」——库里已经提交、事件却可能在补记之前丢失。
 *
 * 顺带解决的是「事件该带哪个版本号」：写回成功后库里的版本恒为 `token.stateVersion + 1`
 * （见 [RcaTaskLeasePort.writeBack]），所以三条会推进版本的路径都不需要回读，
 * 而 [claim] 与 [releaseForRetry] 不推进版本，直接用令牌上的值。
 *
 * ## 事件写入失败会让整次操作回滚
 *
 * 这是契约要求的，不是副作用：事件写不进去意味着「这次迁移没有可对应的历史」，
 * 而一条没有历史的状态迁移在事后无法与「凭空出现的状态」区分开。
 * 代价要说清：`t_rca_domain_event` 不可用会连带让任务领取也失败（可用性上的耦合）。
 * 换来的是历史完整性，这个交换是刻意的。
 */
@Component
class RcaTaskLeaseAdapter(
    private val mapper: RcaTaskLeaseMapper,
    private val properties: RcaProperties,
    private val journal: RcaDomainEventJournal
) : RcaTaskLeasePort {

    /**
     * 领取必须在**同一个事务**里完成「加锁取候选 → 条件更新 → 读回」三步。
     *
     * 理由不是 ACID 洁癖：`FOR UPDATE SKIP LOCKED` 的锁在事务结束时才释放，
     * 三者若跨事务，「已加锁但未更新」的窗口会让另一个事务看到一条既不是候选、
     * 也没有新持有者的行——它不属于任何人，只能等下一个人来捡。
     */
    @Transactional
    override fun claim(owner: String, leaseSeconds: Long): RcaClaimOutcome {
        val candidate = mapper.lockClaimCandidate() ?: return RcaClaimOutcome.NoCandidate
        val affected = mapper.claim(
            candidate.id, owner, leaseSeconds, properties.taskTimeoutMinutes
        )
        if (affected != 1) {
            // 领取被并发抢占：这次领取**没有发生**，因此不发事件。发一条「被拒绝的领取」
            // 会让事件流里出现一个既不改变状态、也不对应任何一次执行的条目，
            // 而按事件重建历史的人会把它当成一次真实的领取。
            return RcaClaimOutcome.LostRace(candidate.id)
        }
        val claimed = mapper.findClaimed(candidate.id, owner)
            ?: error(
                "领取后按 (id=${candidate.id}, owner=$owner) 读不回任务行，说明 claim 与 findClaimed 的条件已不一致"
            )
        val token = RcaLeaseToken(
            taskId = claimed.id,
            owner = owner,
            generation = claimed.leaseGeneration,
            stateVersion = claimed.stateVersion,
            episodeId = claimed.episodeId,
            traceId = claimed.traceId
        )
        journal.record(
            token.event(
                type = RcaEventType.TASK_CLAIMED,
                // 领取不推进 state_version，所以事件与前后迁移同版本。
                aggregateVersion = claimed.stateVersion,
                payload = linkedMapOf(
                    "leaseGeneration" to claimed.leaseGeneration,
                    "attemptCount" to claimed.attemptCount,
                    "leaseOwner" to owner
                )
            )
        )
        return RcaClaimOutcome.Claimed(token = token, task = claimed.toClaimedTask())
    }

    override fun renew(token: RcaLeaseToken, leaseSeconds: Long): RcaLeaseRenewal =
        applied(mapper.renew(token.taskId, token.owner, token.generation, leaseSeconds))
            .let { if (it == RcaWriteBackResult.APPLIED) RcaLeaseRenewal.RENEWED else RcaLeaseRenewal.LEASE_LOST }

    /**
     * 写回 + 记事件同一个事务。
     *
     * 只有 `APPLIED` 才记：`FENCED_OUT` 意味着这次写回**没有生效**，
     * 记一条事件等于给历史添一段没发生过的迁移——而这恰恰是事件日志要防的错误，
     * 只不过这次的来源是它自己。
     */
    @Transactional
    override fun writeBack(token: RcaLeaseToken, mutation: RcaTaskMutation): RcaWriteBackResult {
        val result = applied(
            mapper.writeBack(
                id = token.taskId,
                owner = token.owner,
                generation = token.generation,
                stateVersion = token.stateVersion,
                currentNode = mutation.nextNode?.storageCode ?: DONE_NODE,
                taskStatus = mutation.taskStatus.storageCode,
                stopReason = mutation.stopReason?.storageCode,
                stateSnapshot = mutation.stateSnapshot,
                snapshotSha256 = mutation.snapshotSha256,
                stepsUsed = mutation.stepsUsed,
                tokensUsed = mutation.tokensUsed,
                costUsedUsd = mutation.costUsedUsd
            )
        )
        if (result != RcaWriteBackResult.APPLIED) return result

        // 终态与中间迁移用两个事件类型，而不是靠载荷里的 taskStatus 区分：
        // 订阅方最常问的两个问题（「这个任务结束了吗」与「它走到哪一步了」）应该由
        // 事件类型直接回答，否则每个订阅方都要自己去认那组状态编码。
        val settled = mutation.taskStatus.terminal
        journal.record(
            token.event(
                type = if (settled) RcaEventType.TASK_SETTLED else RcaEventType.TASK_STATE_TRANSITIONED,
                aggregateVersion = token.stateVersion + 1,
                payload = linkedMapOf(
                    "taskStatus" to mutation.taskStatus.storageCode,
                    "stopReason" to mutation.stopReason?.storageCode,
                    // 中间迁移写的是「下一个要进入的节点」；终态时它必然是 null
                    // （RcaTaskMutation 的不变式保证），这里如实落 null 而不是省略该键。
                    "nextNode" to mutation.nextNode?.storageCode
                )
            )
        )
        return result
    }

    /**
     * 让出等待重试 + 记事件同一个事务。
     *
     * 载荷里只放 `errorCode`，**不放 `errorDetail`**：后者可能带上游响应片段，
     * 而事件会离开本进程（见 [RcaDomainEvent] 的载荷禁令）。错误详情已经在任务行上，
     * 人工排查看那里。
     */
    @Transactional
    override fun releaseForRetry(token: RcaLeaseToken, errorCode: String, errorDetail: String): RcaWriteBackResult {
        val result = applied(
            mapper.releaseForRetry(
                token.taskId,
                token.owner,
                token.generation,
                errorCode,
                errorDetail.take(DETAIL_MAX_LENGTH)
            )
        )
        if (result != RcaWriteBackResult.APPLIED) return result
        journal.record(
            token.event(
                type = RcaEventType.TASK_RELEASED,
                // 让出不推进 state_version（SQL 里只改 task_status 与租约两列）。
                aggregateVersion = token.stateVersion,
                payload = linkedMapOf(
                    "errorCode" to errorCode,
                    "leaseOwner" to token.owner
                )
            )
        )
        return result
    }

    @Transactional
    override fun abandon(
        token: RcaLeaseToken,
        status: RcaTaskStatus,
        stopReason: RcaStopReason,
        errorCode: String,
        errorDetail: String
    ): RcaWriteBackResult {
        val result = applied(
            mapper.abandon(
                token.taskId, token.owner, token.generation,
                status.storageCode, stopReason.storageCode, errorCode, errorDetail.take(DETAIL_MAX_LENGTH)
            )
        )
        if (result != RcaWriteBackResult.APPLIED) return result
        journal.record(
            token.event(
                type = RcaEventType.TASK_ABANDONED,
                aggregateVersion = token.stateVersion + 1,
                payload = linkedMapOf(
                    "taskStatus" to status.storageCode,
                    "stopReason" to stopReason.storageCode,
                    "errorCode" to errorCode,
                    // 收口会把 lease_owner 清空，所以「是谁收口的」只能从这里留下。
                    "leaseOwner" to token.owner
                )
            )
        )
        return result
    }

    /**
     * 用令牌上的随行事实补齐事件的公共字段。
     *
     * 放在这里而不是每个写方法里各拼一次：`aggregateType` 与 `aggregateId` 的取值口径
     * 一旦在某一条路径上写歪（例如把 `aggregateId` 填成了 `episodeId`），
     * 事件仍然能插进去（唯一键只约束 `event_id`），只有事后按聚合捞不到东西时才会被发现。
     */
    private fun RcaLeaseToken.event(
        type: RcaEventType,
        aggregateVersion: Long,
        payload: Map<String, Any?>
    ): RcaDomainEvent = RcaDomainEvent(
        aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
        aggregateId = taskId,
        aggregateVersion = aggregateVersion,
        type = type,
        taskId = taskId,
        episodeId = episodeId,
        traceId = traceId,
        payload = payload
    )

    /** 影响行数到领域结果的唯一转换点：1 表示生效，0 表示被 fencing 拒绝。 */
    private fun applied(affectedRows: Int): RcaWriteBackResult = when (affectedRows) {
        1 -> RcaWriteBackResult.APPLIED
        // 0 行是正常路径，不是异常：租约已被接管或状态已被推进。大于 1 才是真异常——
        // 主键更新不可能影响多行，出现即说明语句写错了（例如漏了主键条件）。
        0 -> RcaWriteBackResult.FENCED_OUT
        else -> error("主键更新的影响行数为 $affectedRows，语句条件有误")
    }

    /**
     * `current_node` 里的 `DONE` 不是节点，而是「流程已收口」的标记。
     *
     * 反向转换时它对 [RcaNode] 没有对应项；但可领取的任务状态只有 `PENDING` / `RETRY_PENDING`，
     * 这类任务必然还有下一个节点要进入，所以真的取到 `DONE` 只能是数据不一致——
     * 那时必须炸，而不是回退到某个默认节点重新跑一遍。
     */
    private fun RcaAgentTaskPo.toClaimedTask(): RcaClaimedTask {
        val node = RcaNode.fromStorage(currentNode)
            ?: error("可领取的任务 id=$id 的下一个节点是 '$currentNode'，不是合法节点，属数据不一致")
        return RcaClaimedTask(
            taskId = id,
            taskKey = taskKey.orEmpty(),
            episodeId = episodeId,
            currentNode = node,
            stateSnapshot = stateSnapshot,
            attemptCount = attemptCount,
            maxAttempts = maxAttempts,
            leaseRemainingMs = leaseRemainingMs,
            deadlineRemainingMs = deadlineRemainingMs,
            // 原样搬运，不做兜底：库里没有标识就如实交出去，由 Worker 决定「不做链路落库」。
            traceId = traceId
        )
    }

    private companion object {
        /** `chk_rca_agent_node` 允许的收口标记。 */
        const val DONE_NODE = "DONE"

        /** `t_rca_agent_task.error_detail` 是 VARCHAR(1000)，超长会在数据库侧被截断或报错。 */
        const val DETAIL_MAX_LENGTH = 1000
    }
}
