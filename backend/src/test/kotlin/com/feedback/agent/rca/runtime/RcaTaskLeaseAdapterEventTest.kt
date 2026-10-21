package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.config.RcaProperties
import com.feedback.mapper.rca.RcaTaskLeaseMapper
import com.feedback.model.po.rca.RcaAgentTaskPo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 租约适配器发出的领域事件：**哪条路径发、哪条路径不发、版本号是多少**。
 *
 * ## 为什么这一条不能只靠 `rcaEntryTest` 验
 *
 * 真实库那一侧能验的是「事件行确实出现了」，验不了「不该出现的没出现」：
 * 一个被 fencing 拒绝的写回如果也记了一条事件，库里多一行，而没有任何断言会看它。
 * 那条事件描述的是**一次没有发生过的状态迁移**——正是事件日志要防的那种错误，
 * 只不过来源换成了日志自己。
 *
 * 本类的三组用例各守一件事：
 *
 * 1. **被拒绝、被抢占、没候选**：三条路径都必须零事件；
 * 2. **版本号**：推进 `state_version` 的两条（`writeBack` / `abandon`）写 `token + 1`，
 *    不推进的两条（`claim` / `releaseForRetry`）写令牌原值——写错一个就会让事件与快照
 *    指向不同的版本，而按版本重建历史的人会认为中间丢了一次迁移；
 * 3. **随行事实**：`episodeId` / `traceId` 来自令牌，其中 `leaseOwner` 只能从载荷里留下——
 *    收口会把任务行的 `lease_owner` 清空，事件是它最后出现的地方。
 *
 * 用假映射器（不连库）。`@Transactional` 在单测里不生效，这是刻意的：
 * 这里验的是「发不发、发什么」，事务边界本身由 `rcaEntryTest` 对真实库验。
 */
class RcaTaskLeaseAdapterEventTest {

    private val mapper = FakeMapper()
    private val journal = RecordingJournal()
    private val adapter = RcaTaskLeaseAdapter(mapper, RcaProperties(), journal)

    // ---------------------------------------------------------------------
    // 领取
    // ---------------------------------------------------------------------

    @Test
    fun `领取成功记一条 TASK_CLAIMED，版本不推进`() {
        mapper.candidate = row()
        mapper.claimedRow = row(stateVersion = 4, leaseGeneration = 3, attemptCount = 3)

        val outcome = adapter.claim(OWNER, LEASE_SECONDS)
        assertTrue(outcome is RcaClaimOutcome.Claimed, "领取应当成功")

        val event = journal.events.single()
        assertEquals(RcaEventType.TASK_CLAIMED, event.type)
        assertEquals(
            4L, event.aggregateVersion,
            "领取不推进 state_version（SQL 里没写那一列），事件却报了一个推进后的版本：" +
                "按版本重建历史的人会认为中间发生了一次不存在的迁移"
        )
        assertEquals(mapOf("leaseGeneration" to 3L, "attemptCount" to 3, "leaseOwner" to OWNER), event.payload)
    }

    @Test
    fun `领取被并发抢占时不记事件`() {
        mapper.candidate = row()
        mapper.claimResult = 0

        val outcome = adapter.claim(OWNER, LEASE_SECONDS)
        assertTrue(outcome is RcaClaimOutcome.LostRace)
        assertEquals(
            emptyList<RcaDomainEvent>(), journal.events,
            "这次领取在世界上没有发生，却留下了一条事件：按事件重建历史的人会把它当成一次真实的领取"
        )
    }

    @Test
    fun `没有候选时什么也不做`() {
        mapper.candidate = null
        assertEquals(RcaClaimOutcome.NoCandidate, adapter.claim(OWNER, LEASE_SECONDS))
        assertEquals(emptyList<RcaDomainEvent>(), journal.events)
    }

    // ---------------------------------------------------------------------
    // 写回
    // ---------------------------------------------------------------------

    @Test
    fun `中间写回记 TASK_STATE_TRANSITIONED，版本推进一版`() {
        mapper.writeBackResult = 1

        val result = adapter.writeBack(token(stateVersion = 4), mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST))
        assertEquals(RcaWriteBackResult.APPLIED, result)

        val event = journal.events.single()
        assertEquals(RcaEventType.TASK_STATE_TRANSITIONED, event.type)
        assertEquals(
            5L, event.aggregateVersion,
            "写回成功后库里的版本恒为 token + 1（语句只在版本相等时生效），事件必须报同一个值"
        )
        assertEquals(RcaTaskStatus.RUNNING.storageCode, event.payload["taskStatus"])
        assertEquals(
            RcaNode.RCA_PERSIST.storageCode, event.payload["nextNode"],
            "中间迁移的载荷里必须是「下一个要进入的节点」：它正是恢复时要读的那个值"
        )
    }

    @Test
    fun `终态写回记 TASK_SETTLED 而不是 TASK_STATE_TRANSITIONED`() {
        mapper.writeBackResult = 1

        adapter.writeBack(
            token(stateVersion = 4),
            mutation(RcaTaskStatus.NEEDS_HUMAN, nextNode = null, stopReason = RcaStopReason.HUMAN_REQUIRED)
        )

        val event = journal.events.single()
        // 订阅方最常问的两个问题（「这个任务结束了吗」与「它走到哪一步了」）应当由事件类型直接回答，
        // 而不是让每个订阅方各自去认那组状态编码。
        assertEquals(RcaEventType.TASK_SETTLED, event.type)
        assertEquals(5L, event.aggregateVersion)
        assertEquals(RcaStopReason.HUMAN_REQUIRED.storageCode, event.payload["stopReason"])
        assertNull(
            event.payload["nextNode"],
            "终态的载荷里 nextNode 必须是显式的 null（而不是省略该键）：省略之后，" +
                "订阅方无法区分「流程走完」与「这一版的事件生成器忘了填」"
        )
    }

    @Test
    fun `写回被 fencing 拒绝时不记事件`() {
        mapper.writeBackResult = 0

        val result = adapter.writeBack(token(stateVersion = 4), mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST))
        assertEquals(RcaWriteBackResult.FENCED_OUT, result)
        assertEquals(
            emptyList<RcaDomainEvent>(), journal.events,
            "被 fencing 拒绝的写回没有改变任何状态，却被记了一条迁移事件——" +
                "这类错误比「少记一条」更难发现，因为库里只会多出一段无法解释的历史"
        )
    }

    // ---------------------------------------------------------------------
    // 让出与收口
    // ---------------------------------------------------------------------

    @Test
    fun `让出等待重试记 TASK_RELEASED，版本不推进`() {
        mapper.releaseResult = 1

        adapter.releaseForRetry(token(stateVersion = 4), "RCA_TIMEOUT", "detail")

        val event = journal.events.single()
        assertEquals(RcaEventType.TASK_RELEASED, event.type)
        assertEquals(
            4L, event.aggregateVersion,
            "让出只改 task_status 与租约两列，不推进 state_version"
        )
        assertEquals("RCA_TIMEOUT", event.payload["errorCode"])
        assertTrue(
            event.payload.values.none { it is String && it.contains("detail") },
            "错误详情进了会离开本进程的载荷：它可能带上游响应片段，人工排查看任务行上的那一列就够"
        )
    }

    @Test
    fun `收口记 TASK_ABANDONED，版本推进一版，并留下收口者`() {
        mapper.abandonResult = 1

        adapter.abandon(
            token(stateVersion = 4),
            RcaTaskStatus.NEEDS_HUMAN,
            RcaStopReason.HUMAN_REQUIRED,
            "RCA_RECOVERY_UNRESOLVED",
            "detail"
        )

        val event = journal.events.single()
        assertEquals(RcaEventType.TASK_ABANDONED, event.type)
        assertEquals(5L, event.aggregateVersion, "abandon 的 SQL 里有 state_version = state_version + 1")
        assertEquals(
            OWNER, event.payload["leaseOwner"],
            "收口会把任务行的 lease_owner 清空，事件是「是谁收口的」最后能留下的地方"
        )
        assertEquals(RcaTaskStatus.NEEDS_HUMAN.storageCode, event.payload["taskStatus"])
    }

    @Test
    fun `收口被 fencing 拒绝时不记事件`() {
        mapper.abandonResult = 0
        adapter.abandon(
            token(stateVersion = 4),
            RcaTaskStatus.NEEDS_HUMAN,
            RcaStopReason.HUMAN_REQUIRED,
            "RCA_RECOVERY_UNRESOLVED",
            "detail"
        )
        assertEquals(emptyList<RcaDomainEvent>(), journal.events)
    }

    // ---------------------------------------------------------------------
    // 随行事实
    // ---------------------------------------------------------------------

    @Test
    fun `事件的会话与链路标识来自令牌，为空时不兜底`() {
        mapper.writeBackResult = 1
        adapter.writeBack(token(stateVersion = 4, traceId = null), mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST))

        val event = journal.events.single()
        assertEquals(EPISODE_ID, event.episodeId)
        assertNull(
            event.traceId,
            "令牌上没有链路标识时事件被补了一个：事后将无法区分「这次执行真的没有标识」与「我们填了一个」"
        )
        assertEquals(RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK, event.aggregateType)
        assertEquals(TASK_ID, event.aggregateId)
        assertEquals(TASK_ID, event.taskId)
    }

    // ---------------------------------------------------------------------

    private fun token(stateVersion: Long, traceId: String? = TRACE_ID) = RcaLeaseToken(
        taskId = TASK_ID,
        owner = OWNER,
        generation = 3,
        stateVersion = stateVersion,
        episodeId = EPISODE_ID,
        traceId = traceId
    )

    private fun mutation(
        status: RcaTaskStatus,
        nextNode: RcaNode?,
        stopReason: RcaStopReason? = null
    ) = RcaTaskMutation(
        nextNode = nextNode,
        taskStatus = status,
        stopReason = stopReason,
        stateSnapshot = "{}",
        snapshotSha256 = "0".repeat(64),
        stepsUsed = 1,
        tokensUsed = 10,
        costUsedUsd = BigDecimal.ZERO
    )

    private fun row(
        stateVersion: Long = 0,
        leaseGeneration: Long = 1,
        attemptCount: Int = 1
    ) = RcaAgentTaskPo(
        id = TASK_ID,
        taskKey = "rca-key",
        episodeId = EPISODE_ID,
        taskStatus = RcaTaskStatus.PENDING.storageCode,
        currentNode = RcaNode.CONTEXT_BUILD.storageCode,
        stateVersion = stateVersion,
        leaseOwner = null,
        leaseGeneration = leaseGeneration,
        attemptCount = attemptCount,
        maxAttempts = 3,
        traceId = TRACE_ID
    )

    private class RecordingJournal : RcaDomainEventJournal {
        val events = mutableListOf<RcaDomainEvent>()
        override fun record(event: RcaDomainEvent) {
            events += event
        }
    }

    private class FakeMapper : RcaTaskLeaseMapper {

        var candidate: RcaAgentTaskPo? = null
        var claimResult = 1
        var claimedRow: RcaAgentTaskPo? = null
        var writeBackResult = 1
        var releaseResult = 1
        var abandonResult = 1

        override fun lockClaimCandidate(): RcaAgentTaskPo? = candidate

        override fun claim(id: Long, owner: String, leaseSeconds: Long, taskTimeoutMinutes: Long): Int = claimResult

        override fun findClaimed(id: Long, owner: String): RcaAgentTaskPo? = claimedRow

        override fun renew(id: Long, owner: String, generation: Long, leaseSeconds: Long): Int = 0

        override fun writeBack(
            id: Long,
            owner: String,
            generation: Long,
            stateVersion: Long,
            currentNode: String,
            taskStatus: String,
            stopReason: String?,
            stateSnapshot: String,
            snapshotSha256: String,
            stepsUsed: Int,
            tokensUsed: Int,
            costUsedUsd: BigDecimal
        ): Int = writeBackResult

        override fun releaseForRetry(
            id: Long,
            owner: String,
            generation: Long,
            errorCode: String,
            errorDetail: String
        ): Int = releaseResult

        override fun abandon(
            id: Long,
            owner: String,
            generation: Long,
            taskStatus: String,
            stopReason: String,
            errorCode: String,
            errorDetail: String
        ): Int = abandonResult
    }

    private companion object {
        const val TASK_ID = 42L
        const val EPISODE_ID = 7L
        const val OWNER = "worker-a"
        const val LEASE_SECONDS = 180L
        const val TRACE_ID = "0123456789abcdef0123456789abcdef"
    }
}
