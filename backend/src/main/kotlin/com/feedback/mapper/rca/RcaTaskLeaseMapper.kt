package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaAgentTaskPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * RCA 父任务的租约语句。
 *
 * 这里只放**受租约约束**的写语句。读语句（列表、详情）与不带租约的运维语句
 * （人工重放、取消）不属于本接口，混在一起会让「这个语句是否有 fencing」变得需要逐条分辨。
 *
 * 所有方法都返回影响行数，由适配器翻译成领域结果。**影响行数为 0 不是错误**：
 * 它表示 fencing 生效了，因此这里不做任何「必须等于 1」的断言——
 * 那种断言会把一次正常的租约丢失变成抛异常，而正确处理是丢弃结果。
 */
@Mapper
interface RcaTaskLeaseMapper {

    /**
     * 取一个可领取的候选并加行锁。`SKIP LOCKED` 让并发 Worker 直接跳过被锁住的行，
     * 而不是排队等待——等待会把「多个 Worker 抢同一个任务」变成「所有 Worker 串行化」。
     *
     * 候选条件里 `task_status` **含 `RUNNING`**，判据是租约是否已失效而不是状态是否待处理：
     * Worker 被杀时不会执行任何让出语句，行就停在 `RUNNING` 上，
     * 只挑 `PENDING` / `RETRY_PENDING` 会让这类任务永远不再被选中——既不是终态，
     * 也不会出现在任何「待处理」看板里，等于静默丢失。
     * 接管一条 `RUNNING` 的任务之所以安全，是因为旧的持有者已经不可能再写回：
     * 那条路径由 `lease_generation` 与 `state_version` 双重条件封住（见 [writeBack]）。
     *
     * **候选条件里没有 `deadline_at`**：超期任务同样会被选中，由 Worker 领取后立刻收口。
     * 排除它反而会让它永远停在非终态上（见 XML 里的说明）。
     */
    fun lockClaimCandidate(): RcaAgentTaskPo?

    /**
     * 条件领取：把候选变成本次持有。
     *
     * 四件事必须在同一条语句里完成，否则会留下窗口：
     * 1. 置 `RUNNING` 并写入 owner；
     * 2. **递增 `lease_generation`** —— 这是 fencing 的来源；
     * 3. 用数据库时间续上 `lease_until`；
     * 4. 首次领取时补上 `deadline_at`（`IFNULL`，见参数）。
     *
     * `WHERE` 里重复一遍候选条件不是冗余：从加锁到执行 UPDATE 之间，
     * 行可能已被另一个事务改成终态。条件更新是唯一能把这次检查与实际写入绑在一起的手段。
     *
     * @param taskTimeoutMinutes 任务整体的墙钟上限，**首次领取**时由 `NOW(3)` 推出
     *        `deadline_at`。取 `app.rca.task-timeout-minutes`，必须与
     *        `RcaBudgetPolicy` 的截止时间同源：两处给出不同的时长会让「库上还没超期、
     *        内存里已判超期」这类差异无法归因。
     */
    fun claim(
        @Param("id") id: Long,
        @Param("owner") owner: String,
        @Param("leaseSeconds") leaseSeconds: Long,
        @Param("taskTimeoutMinutes") taskTimeoutMinutes: Long
    ): Int

    /**
     * 读回本次领取的结果。
     *
     * 按 `(id, owner)` 而不是按 generation 查：owner 是本 Worker 本次运行唯一的随机标识，
     * 因此这一个查询只会命中自己刚写下的那一行。若按 generation 查，
     * 就得先知道 generation 是多少——那正是本方法要回答的问题。
     *
     * 返回的行里带 `leaseRemainingMs` 与 `deadlineRemainingMs` 两个**由数据库算出的差值**。
     * Worker 只消费差值，不把绝对时间点换算到自己的时钟上：一旦换算，
     * 「租约/截止时间到了没有」就重新变成由 Worker 的墙钟决定，而这正是用 `NOW(3)` 想消除的东西。
     */
    fun findClaimed(@Param("id") id: Long, @Param("owner") owner: String): RcaAgentTaskPo?

    /**
     * 续租。条件为 owner + generation + `RUNNING`，**不含 state_version**。
     *
     * `lease_until` 的新值由数据库时间推算，Worker 只提供时长：让 Worker 传绝对时间点
     * 会把它的时钟漂移直接写进租约，慢的 Worker 会给自己续出一个更长的租约。
     */
    fun renew(
        @Param("id") id: Long,
        @Param("owner") owner: String,
        @Param("generation") generation: Long,
        @Param("leaseSeconds") leaseSeconds: Long
    ): Int

    /**
     * 状态写回。四重条件：id + owner + generation + state_version。
     *
     * 刻意**不加** `lease_until >= NOW(3)`：租约存活与否是 Worker 自己判断「要不要继续干」的依据，
     * 而写回该不该被接受由 generation 决定。若把租约存活也写进写回条件，
     * 一个执行时间略长于租约、但期间无人接管的节点会白干——它算完了，却因为没有及时续租被拒，
     * 而它的结果本来是完全有效的。
     */
    fun writeBack(
        @Param("id") id: Long,
        @Param("owner") owner: String,
        @Param("generation") generation: Long,
        @Param("stateVersion") stateVersion: Long,
        @Param("currentNode") currentNode: String,
        @Param("taskStatus") taskStatus: String,
        @Param("stopReason") stopReason: String?,
        @Param("stateSnapshot") stateSnapshot: String,
        @Param("snapshotSha256") snapshotSha256: String,
        @Param("stepsUsed") stepsUsed: Int,
        @Param("tokensUsed") tokensUsed: Int,
        @Param("costUsedUsd") costUsedUsd: java.math.BigDecimal
    ): Int

    /**
     * 让出任务等待重试。
     *
     * 必须同时递增 `attempt_count`：attempt 上限是「一个任务最多被尝试几次」的硬约束，
     * 只清租约而不计次会让一个必然失败的任务被无限重试，把队列拖成黑洞。
     */
    fun releaseForRetry(
        @Param("id") id: Long,
        @Param("owner") owner: String,
        @Param("generation") generation: Long,
        @Param("errorCode") errorCode: String,
        @Param("errorDetail") errorDetail: String
    ): Int

    /** 收口到非正常终态；条件同样只含 owner + generation。 */
    fun abandon(
        @Param("id") id: Long,
        @Param("owner") owner: String,
        @Param("generation") generation: Long,
        @Param("taskStatus") taskStatus: String,
        @Param("stopReason") stopReason: String,
        @Param("errorCode") errorCode: String,
        @Param("errorDetail") errorDetail: String
    ): Int
}
