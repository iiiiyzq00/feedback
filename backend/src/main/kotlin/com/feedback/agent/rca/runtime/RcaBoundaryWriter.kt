package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaTaskStatus

/**
 * 节点边界上的租约动作：续租 + 状态落库。
 *
 * 从 [RcaWorker] 里抽出来单独成类，不是为了「拆得小一点」，而是因为故障注入必须**逐字复用**
 * 同一段逻辑：注入测试要模拟「Worker 在边界上崩溃」，如果它自己另写一遍续租与写回，
 * 那它验证的就是那份复制品，而不是生产会走的那条路。
 *
 * 边界上有两个动作，顺序不能颠倒：
 * 1. **先续租**。续租是「我还活着」的心跳，也是最早能发现自己已被接管的地方。
 *    晚于写回的话，一个租约已被接管的 Worker 会先成功写一次状态、再发现自己该停下——
 *    那次写回会把新持有者的状态覆盖回去，而这正是 generation 要挡的事。
 * 2. **再写回**。写回带 `state_version` 条件，成功即推进一版，令牌据此前进。
 *
 * [token] 的推进**由本类负责**而不是由调用方：写回成功与令牌前进必须成对发生，
 * 分成两处写就一定会出现「写回成功但忘了推进令牌」，后果是下一次写回条件用的是旧版本号，
 * 于是它被自己的前一次写回 fencing 掉——现象看起来像并发争抢，实际是漏了一行赋值。
 */
class RcaBoundaryWriter(
    private val leases: RcaTaskLeasePort,
    private val codec: RcaTaskSnapshotCodec,
    private val owner: String,
    private val leaseSeconds: Long,
    initialToken: RcaLeaseToken
) {

    /** 当前令牌。调用方在写回终态时使用它。 */
    var token: RcaLeaseToken = initialToken
        private set

    /** 是否已经判定租约丢失。为 true 后 [onBoundary] 一律返回 false，不再触碰数据库。 */
    var leaseLost: Boolean = false
        private set

    private var crossedStart = false

    /**
     * 边界钩子本体。返回 false 表示调用方必须中止本次运行。
     *
     * **第一个边界不做任何写回**：领取时读到的快照就是「即将进入首个节点时的状态」，
     * 它刚落库过（新建任务的初始状态亦然）。在第一个边界再写一次只会让 `state_version`
     * 白增一版，使「版本号 = 有效迁移次数」这个语义失真。
     */
    fun onBoundary(nextNode: RcaNode, state: RcaSharedState): Boolean {
        if (leaseLost) return false

        if (!crossedStart) {
            crossedStart = true
            return true
        }

        if (leases.renew(token, leaseSeconds) == RcaLeaseRenewal.LEASE_LOST) {
            leaseLost = true
            return false
        }

        return when (leases.writeBack(token, mutation(nextNode, state))) {
            RcaWriteBackResult.APPLIED -> {
                token = token.copy(stateVersion = token.stateVersion + 1)
                true
            }
            RcaWriteBackResult.FENCED_OUT -> {
                leaseLost = true
                false
            }
        }
    }

    /**
     * 中间边界的写回内容：任务保持 RUNNING、不带停止原因、`next_node` 指向下一个要进入的节点。
     *
     * 预算三项从**状态**里取而不是从数据库读回：状态里的账本才是权威，
     * 数据库那三列只是它的投影。从库里取会丢掉「节点内已消耗但尚未落库」的部分，
     * 于是这部分在恢复后被重复扣减一次。
     */
    private fun mutation(nextNode: RcaNode, state: RcaSharedState): RcaTaskMutation {
        val snapshot = codec.encode(state)
        val budget = state.gapsAndBudget
        return RcaTaskMutation(
            nextNode = nextNode,
            taskStatus = RcaTaskStatus.RUNNING,
            stopReason = null,
            stateSnapshot = snapshot.json,
            snapshotSha256 = snapshot.sha256,
            stepsUsed = budget.stepsUsed,
            tokensUsed = budget.tokensUsed,
            costUsedUsd = budget.costUsedUsd
        )
    }
}
