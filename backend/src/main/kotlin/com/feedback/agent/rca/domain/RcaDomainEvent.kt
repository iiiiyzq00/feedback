package com.feedback.agent.rca.domain

/**
 * RCA 聚合上的一次状态迁移。
 *
 * 事件类型**只描述「聚合发生了什么」**，不描述「谁在什么时候调用了哪个工具」——
 * 后者是执行细节，属于 `t_rca_tool_call`。两者的分界线是：一件事如果改变了聚合的状态
 * （任务状态、当前节点、归因结论），它就是事件；如果只是执行过程中的一个动作，
 * 它就不是。混在一起会让「一次迁移」与「一次动作」的多对多关系被压成一对一。
 *
 * [storageCode] 落库到 `t_rca_domain_event.event_type`，属于稳定协议字段——
 * MQ 订阅方按它分发，改名字等于改对外契约。
 */
enum class RcaEventType(val storageCode: String) {

    /** 任务被某个 Worker 领取。**不改变 `state_version`**（领取不是状态迁移），因此它可以与前后事件同版本。 */
    TASK_CLAIMED("TASK_CLAIMED"),

    /**
     * 任务被让出等待重试（异常退出、依赖不可用），`task_status` 回到 `RETRY_PENDING`。
     * 与 [TASK_CLAIMED] 一样**不改变 `state_version`**。
     *
     * 它必须与 [TASK_CLAIMED] 成对存在：只记领取不记让出，历史里会出现 N 次领取而没有任何一次
     * 交代去向，于是「任务被反复领取」既可能是崩溃也可能是正常的重试让出，两者在数据上无法区分——
     * 而那正是排障时第一个要回答的问题。
     */
    TASK_RELEASED("TASK_RELEASED"),

    /** 节点边界上的状态写回成功：任务前进到下一个节点，`state_version` +1。 */
    TASK_STATE_TRANSITIONED("TASK_STATE_TRANSITIONED"),

    /** 流程走到终态并写回成功（含正常完成与图内降级收口）。 */
    TASK_SETTLED("TASK_SETTLED"),

    /** 任务被 Worker 收口到非正常终态（超期、快照损坏、恢复判定不通过、尝试次数耗尽）。 */
    TASK_ABANDONED("TASK_ABANDONED"),

    /** 归因写入 `t_feedback_episode_attribution`。载荷里的 `attributionRevision` 与该表同列同源。 */
    ATTRIBUTION_RECORDED("ATTRIBUTION_RECORDED")
}

/**
 * 一条领域事件。
 *
 * ## 为什么 `aggregateVersion` 是任务的 `state_version` 而不是另造的序号
 *
 * 教科书式事件溯源要求「一个事件 = 聚合版本 +1」。本仓库**刻意不这么做**：
 * `t_rca_agent_task.state_version` 是快照恢复机制的一部分（`writeBack`/`abandon` 各 +1，
 * `claim`/`releaseForRetry` 不动它），把它改成「一事件一版本」会同时改掉
 * [com.feedback.agent.rca.runtime.RcaBoundaryWriter] 的令牌推进与
 * [com.feedback.agent.rca.runtime.RcaClaimedTask] 的读回口径——那是一场与「记录历史」
 * 无关的高风险改动。
 *
 * 代价必须一起说清，否则读的人会以为这一列比它实际能回答的更多：
 * **同一版本上可以有多条事件**（`TASK_CLAIMED` 不递增版本），
 * 且**不能据版本号是否连续判断事件有没有丢**。聚合内的顺序由
 * `(aggregateVersion, 落库自增 id)` 共同决定。
 *
 * ## 载荷里不许出现正文与用户标识
 *
 * [payload] 会被 MQ 中继**原样发到主题**，从而离开本进程的信任边界。
 * 允许装的是两类东西，且只有这两类：
 *
 * - **结构化的判据**：节点编码、任务状态、停止原因、错误码、归因等级、计数、版本号；
 * - **本系统自己的执行者标识**：Worker 名（`lease_owner`）。
 *
 * 第二类需要单独说明，因为它看起来像是「用户标识」的近亲：它不是。它由部署方自己指派
 * （主机名/容器名），指的是「哪台机器上的哪个执行者」，而不是任何一个反馈提交者。
 * 把它放进载荷是因为 `lease_owner` 在终态写回时会被清空（`writeBack`/`abandon` 都置 NULL），
 * 于是「这个任务是被谁收口的」在事件之外已经无从查证，而它是排查「同一类故障在
 * 两台 Worker 上得到不同处置」时唯一能区分两者的字段。
 *
 * **禁止**的是：反馈正文、联系方式、指纹、日志内容、堆栈正文、上游响应片段、
 * `error_detail` 的原文（它可能带上游响应片段，且在库里已经是会被人工直接看到的列，
 * 不需要再复制一份到会离开进程的地方）。
 */
data class RcaDomainEvent(
    /** 聚合类型。今天只有 RCA 任务这一种，保留该字段是为了将来接入其它聚合时不改表。 */
    val aggregateType: String,
    /** 聚合标识。今天等于 [taskId]。 */
    val aggregateId: Long,
    /** 事件发生时聚合的版本，取自任务的 `state_version`。语义与限制见类文档。 */
    val aggregateVersion: Long,
    val type: RcaEventType,
    val taskId: Long,
    /** 会话标识，冗余自任务行：按会话捞事件时不必先查任务表。为 null 表示任务行上还没有值。 */
    val episodeId: Long? = null,
    /** 冗余自任务行。为 null **不兜底**：编一个出来会让「这次执行有没有链路标识」永远无法回答。 */
    val traceId: String? = null,
    /** 事件细节。值必须是标量或它们的集合——它同时参与 `event_id` 的确定性推导。 */
    val payload: Map<String, Any?> = emptyMap()
) {

    init {
        // 这三条不是防御性编程，是**表上约束的领域侧镜像**。放在这里是因为违反它们的
        // 后果不是「插入失败」，而是「插入成功但这一行解释不通」：
        // 一条版本号为负、或聚合标识与任务标识对不上的事件，事后没有任何办法判断
        // 它记的是哪次迁移，而事件日志恰恰是最后一道取证手段。
        require(aggregateType.isNotBlank()) { "领域事件必须带聚合类型：空类型会让事件无法被按聚合检索" }
        require(aggregateId > 0) { "领域事件的聚合标识必须为正数，当前为 $aggregateId" }
        require(aggregateVersion >= 0) { "领域事件的聚合版本不能为负，当前为 $aggregateVersion" }
        require(taskId > 0) { "领域事件必须挂在具体任务上，当前 taskId=$taskId" }
    }

    companion object {
        /** 今天唯一的聚合类型。常量而不是散落的字面量：它同时进 `event_id` 的推导。 */
        const val AGGREGATE_RCA_AGENT_TASK = "RCA_AGENT_TASK"
    }
}

/**
 * 领域事件日志端口。
 *
 * ## 契约：写入必须与状态迁移在**同一个事务**里
 *
 * 实现必须保证「状态迁移已提交」与「对应事件已提交」同时成立或同时不成立。
 * 这不是风格问题：事件的用途是回答「历史发生了什么」，一条记在回滚事务里的事件
 * 会凭空造出一段没发生过的历史，而一条丢失的事件会让历史出现无法解释的断层——
 * 两种错误都比「什么都不记」更难发现。
 *
 * 生产实现落在 [com.feedback.agent.rca.runtime.RcaTaskLeaseAdapter] 这类已经有
 * `@Transactional` 的适配器方法内部，因此**它没有自己的事务边界**，也不该有。
 *
 * ## 为什么默认是 [NONE]
 *
 * 与 [com.feedback.agent.rca.runtime.RcaRecoveryResolver.NONE] 同一条纪律：
 * 「这一层没有接」必须是一个显式的、能被读出来的取值，而不是一个恰好什么都不做的实现。
 * 离线回放与故障注入套件用 [NONE]，因为它们的断言建立在「逐字节相同的快照」上，
 * 而事件表既不在快照里、也不该在离线夹具里被写。
 */
fun interface RcaDomainEventJournal {

    /**
     * 记录一条事件。
     *
     * **不吞异常**：写不进去就让调用方的事务回滚。把事件写入失败降级成一条日志，
     * 等于让「历史完整性」在一个静默的分支上被悄悄放弃。
     */
    fun record(event: RcaDomainEvent)

    companion object {
        /** 未接入实现。语义是「这个部署没有事件日志」，不是「默认安全」。 */
        val NONE: RcaDomainEventJournal = RcaDomainEventJournal { }
    }
}
