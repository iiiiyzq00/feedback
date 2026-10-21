package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import java.math.BigDecimal

/**
 * 租约令牌：一次领取的全部凭证。
 *
 * 四个字段缺一不可，它们各封一类竞态：
 * - [taskId]：写的是哪个任务。
 * - [owner]：谁是持有者。只匹配 id 时，任何 Worker 都能写任意任务。
 * - [generation]：**领取的代数**。这是 fencing 的核心。`owner` 单独用会留下 ABA 缺口——
 *   Worker 名可能复用（重启后同名、宿主名相同、容器名相同），一旦复用，
 *   「A 领取 → 租约过期 → B 重新领取 → A 晚到的写回」会被 `owner` 条件放行。
 *   领取时递增 generation 之后，A 手里的令牌代数已经落后，写回影响行数必然是 0。
 * - [stateVersion]：**状态的版本**。generation 封的是「谁在写」，
 *   stateVersion 封的是「基于哪一版状态在写」，两者防的不是同一件事：
 *   同一持有者内部也会出现旧快照覆盖新状态（例如重试时复用了过期快照）。
 *
 * 令牌必须整体携带、整体比较，不允许只挑其中一两个条件——那样 fencing 会退化成
 * 「看起来有防护」，而漏掉的那个条件恰恰是某类事故的唯一防线。
 *
 * ## [episodeId] 与 [traceId] 不是 fencing 条件，是随行的身份事实
 *
 * 这两个字段与上面四个的区别必须说清楚，否则很容易在下一次改动里被顺手加进 `WHERE` 子句：
 * **它们从不参与任何写入条件**。参与条件只会带来一种结果——「会话标识对不上就写不进去」，
 * 而那不是任何一类竞态的防线，只是一次莫名其妙的写失败。
 *
 * 它们在这里是因为写入侧需要它们、而重新读一次数据库不需要：
 * `t_rca_domain_event` 的 `episode_id` / `trace_id` 是冗余列（见该表 DDL），
 * 而与状态迁移同一个事务里发出的事件必须带上它们，`claim` 读回任务行时已经拿到了这两个值。
 * 不让适配器为每次边界写回再查一次任务表，是为了避免**每个节点一次**的多余读，
 * 也是为了避免那次读数与写回所基于的版本之间出现不一致。
 *
 * 为 null 的含义与 [RcaClaimedTask.traceId] 一致：**这一列为空就是空**，
 * 不许在事件侧补一个值出来。
 */
data class RcaLeaseToken(
    val taskId: Long,
    val owner: String,
    val generation: Long,
    val stateVersion: Long,
    /** 会话标识，随行事实，**不参与 fencing**。见类文档。 */
    val episodeId: Long? = null,
    /** 本次执行的链路标识，随行事实，**不参与 fencing**。见类文档。 */
    val traceId: String? = null
)

/**
 * 领取结果。
 *
 * 「没领到」与「领取被拒」必须能区分：前者是正常的空转（队列空），
 * 后者说明有并发争抢，指标上要分开统计，否则「领取成功率」会被空队列拉低而看不出争抢。
 */
sealed interface RcaClaimOutcome {

    /** 领取成功。[task] 是本次领到的任务快照，[token] 是后续续租与写回的唯一凭证。 */
    data class Claimed(val token: RcaLeaseToken, val task: RcaClaimedTask) : RcaClaimOutcome

    /** 没有可领取的任务。 */
    data object NoCandidate : RcaClaimOutcome

    /**
     * 找到了候选，但条件领取被并发抢占（影响行数为 0）。
     *
     * 这不是错误：`FOR UPDATE SKIP LOCKED` 已经把大部分争抢挡在锁外，
     * 但锁释放到 UPDATE 之间仍有窗口（另一个 Worker 可能提交了终态）。
     * 正确处理是立刻放弃本次机会，而不是重试同一条任务。
     */
    data class LostRace(val taskId: Long) : RcaClaimOutcome
}

/**
 * 已领取任务的快照内容。
 *
 * 只带**恢复所需**的字段，不带完整业务状态：[stateSnapshot] 是不透明的序列化产物，
 * 由 [RcaTaskSnapshotCodec] 解释。这样 Worker 不需要知道共享状态的内部结构，
 * 状态结构变化时也不会波及领取逻辑。
 */
data class RcaClaimedTask(
    val taskId: Long,
    val taskKey: String,
    val episodeId: Long,
    /** **下一个要进入的节点**。语义是「从哪继续」，不是「上次停在哪」。 */
    val currentNode: RcaNode,
    /** 上次落库的状态快照；为 null 表示这是首次进入，从起始节点开始。 */
    val stateSnapshot: String?,
    val attemptCount: Int,
    val maxAttempts: Int,
    /**
     * 租约剩余毫秒数。
     *
     * 由数据库用 `TIMESTAMPDIFF(NOW(3), lease_until)` 算出，Worker 只消费这个差值，
     * 不自己把剩余时间换算成绝对时间点：一旦换算，Worker 的时钟漂移就重新回到了判断链路里，
     * 而这正是「用数据库时间做租约基准」想要消除的东西。
     */
    val leaseRemainingMs: Long,
    /**
     * 截止时间剩余毫秒数，同样由数据库用 `TIMESTAMPDIFF(NOW(3), deadline_at)` 算出。
     *
     * **超期时为负数**（这一列刻意不夹到 0，见 `RcaAgentTaskPo.deadlineRemainingMs`）；
     * 为 null 表示库里这一列还是 NULL——`claim` 之前的状态，或者由离线夹具显式构造出来的
     * 「没有截止时间」。消费方必须把这两种情况分开：null 不是「超期」，也不是「刚好到期」。
     */
    val deadlineRemainingMs: Long? = null,
    /**
     * 本次执行的链路追踪标识，32 位小写十六进制。
     *
     * **来源是数据库，不是 Worker 自己生成的**：`claim` 语句在领取时把它补上
     * （已存在则原样保留），这里读回的就是那一个值。Worker 若另生成一个，
     * 同一任务跨重试就会得到两个标识，「这个任务执行过几次、每次做了什么」在数据上被劈开。
     *
     * 为 null 表示库里这一列还是 NULL：离线夹具显式构造的「没有标识的执行」，
     * 或者早于该列引入的历史行。这种情况下一律**不做**链路落库（见
     * `RcaToolCallJournalWriter`），而不是退回成编一个标识出来。
     */
    val traceId: String? = null
)

/**
 * 一次状态写回的内容。
 *
 * [nextNode] 的语义是「下一个要进入的节点」，为 null 表示流程已走完。
 * 落库时它写进 `current_node`：非空写节点编码，null 写 `DONE`。
 * 这里用可空而不是给 [RcaNode] 加一个 `DONE` 成员，是因为 `DONE` 不是节点——
 * 它没有处理器、没有执行语义，把它混进节点枚举会让「遍历所有节点做点什么」
 * 这类代码不得不处理一个永远不该被执行的成员。
 */
data class RcaTaskMutation(
    /** 下一个要进入的节点；null 表示流程结束（落库为 `DONE`）。 */
    val nextNode: RcaNode?,
    val taskStatus: RcaTaskStatus,
    val stopReason: RcaStopReason?,
    /** 序列化后的共享状态快照。 */
    val stateSnapshot: String,
    /** 快照内容的 SHA-256，十六进制小写；恢复时按它判断快照是否完整。 */
    val snapshotSha256: String,
    val stepsUsed: Int,
    val tokensUsed: Int,
    val costUsedUsd: BigDecimal
) {
    init {
        // 这条不变式与数据库的 chk_rca_agent_terminal 约束是**同一条**，在领域层先判一次。
        // 理由不是「提前报错更友好」，而是这条约束最容易在两步之间被违反而无人察觉：
        // 「状态已置终态、停止原因还是 null」会让任务无法被任何看板统计到，
        // 也不会被 claim 条件捡走（终态不再是候选），于是它永久静默地停在库里。
        require(taskStatus.terminal == (stopReason != null)) {
            "任务状态 ${taskStatus.storageCode} 与停止原因不匹配：终态必须有停止原因，非终态必须没有"
        }
        // 终态却没有收口：流程结束了却还留着一个「下一个节点」，恢复时会被重新进入。
        require(!taskStatus.terminal || nextNode == null) {
            "任务已是终态 ${taskStatus.storageCode}，不应再留下一个待进入的节点 ${nextNode?.storageCode}"
        }
    }
}

/**
 * 写回结果。
 *
 * **[FENCED_OUT] 是正常路径，不是故障。** 它意味着这个 Worker 手里的令牌已经过期——
 * 租约被接管了，或者状态已被别人推进。此时唯一正确的动作是**整体丢弃本次结果**：
 * 不重试写回（重试只会再失败一次），不回滚别人的状态（那不是它的状态），
 * 也不把结果降级成「部分写入」——部分写入会让新持有者读到一份自相矛盾的状态。
 */
enum class RcaWriteBackResult(val storageCode: String) {
    APPLIED("APPLIED"),
    FENCED_OUT("FENCED_OUT")
}

/** 续租结果。续租失败等价于「我不再是持有者」，Worker 必须立刻停止手上的工作。 */
enum class RcaLeaseRenewal(val storageCode: String) {
    RENEWED("RENEWED"),
    LEASE_LOST("LEASE_LOST")
}

/**
 * 任务租约端口。
 *
 * 所有时间判断都以**数据库时间**为准（见 XML 里的 `NOW(3)`），不使用 Worker 的墙钟：
 * Worker 之间、Worker 与数据库之间的时钟漂移会让「谁的租约先过期」变得不可判定，
 * 而租约判定的正确性完全依赖这个顺序。
 *
 * 五个方法是 Worker 生命周期的完整闭环，没有多余的能力：
 * 领取 → 续租（可选，长节点执行中间做）→ 写回；写回不成功则由 [releaseForRetry]
 * 或 [abandon] 明确交代任务去向，不留「没人管」的中间态。
 */
interface RcaTaskLeasePort {

    /**
     * 原子领取一个任务。
     *
     * 实现必须用 `FOR UPDATE SKIP LOCKED` 或等价的单语句条件更新，
     * 并在同一次操作里递增 `lease_generation`。分成「先查后更新」两步都不行：
     * 两步之间没有任何东西保证候选仍然是候选。
     *
     * @param leaseSeconds 本次租约时长。取值必须大于单节点执行时间，
     *        否则 Worker 会不停地「执行到一半发现租约没了」。
     */
    fun claim(owner: String, leaseSeconds: Long): RcaClaimOutcome

    /**
     * 续租。
     *
     * 续租条件只匹配 owner + generation，**不匹配 state_version**：
     * 续租不基于任何状态，它只是「我还活着」的心跳。
     * 若把 state_version 也放进续租条件，节点执行期间状态推进过的任务会续租失败，
     * 从而在没有任何并发争抢的情况下被判成租约丢失。
     */
    fun renew(token: RcaLeaseToken, leaseSeconds: Long): RcaLeaseRenewal

    /**
     * 带 fencing 的状态写回。影响行数为 0 即 [RcaWriteBackResult.FENCED_OUT]。
     *
     * **写回成功等价于状态推进了一版**：语句里做的是 `state_version = state_version + 1`，
     * 而语句只在 `state_version = token.stateVersion` 时生效，所以写回成功之后
     * 库里的版本恒为 `token.stateVersion + 1`。调用方据此自行推进令牌，
     * 不需要（也不应该）由本方法再返回一个新版本号——两处各算一次迟早会算岔。
     */
    fun writeBack(token: RcaLeaseToken, mutation: RcaTaskMutation): RcaWriteBackResult

    /** 让出任务等待重试（异常退出、依赖不可用）。只匹配 owner + generation。 */
    fun releaseForRetry(token: RcaLeaseToken, errorCode: String, errorDetail: String): RcaWriteBackResult

    /**
     * 任务收口到非正常终态（转人工、失败、取消）。
     *
     * 与 [releaseForRetry] 分开而不是合成一个「写终态」方法：两者的**后续处置完全不同**——
     * 前者会被再次领取，因此必须把租约清干净；后者不会，但要保证停止原因是结构化的，
     * 否则「为什么转人工」在事后只能靠翻日志。
     */
    fun abandon(
        token: RcaLeaseToken,
        status: RcaTaskStatus,
        stopReason: RcaStopReason,
        errorCode: String,
        errorDetail: String
    ): RcaWriteBackResult
}
