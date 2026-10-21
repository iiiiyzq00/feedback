package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaDomainEvent

/**
 * 领域事件标识的确定性推导。
 *
 * ## 为什么必须是确定性的
 *
 * 与 [ToolSpanIdFactory]、[EvidenceIdFactory] 同一条纪律，但理由要更具体一些：
 * 崩溃恢复会**重新进入**同一个节点，于是一次状态迁移可能被算两遍。如果用随机 UUID，
 * 这两遍会在 `t_rca_domain_event` 里留下两条内容相同、标识不同的事件，
 * 事后看到的人只能得出两个结论之一——「它迁移过两次」或者「它迁移过一次但记了两遍」，
 * 而这两个结论对应完全不同的排查方向（前者去查路由，后者去查恢复逻辑）。
 * 确定性标识把这个问题直接消灭：第二次写入撞唯一键，被忽略，库里留下的是**一条**事实。
 *
 * 它同时给了下游一个可用的幂等键：MQ 是至少一次投递，消费方按 `event_id` 去重时，
 * 依赖的正是「同一次逻辑事件在任何一次投递里标识都相同」。
 *
 * ## 形状
 *
 * ```
 * sha256( aggregateType | aggregateId | aggregateVersion | eventType | sha256(载荷规范形式) )
 * ```
 *
 * 取前 32 位小写十六进制，对齐 `chk_rca_domain_event_id` 与 W3C trace-id 的宽度。
 *
 * ## 载荷为什么进摘要
 *
 * 只取 `(聚合, 版本, 类型)` 是不够的：[RcaDomainEvent] 的文档说明了
 * `TASK_CLAIMED` 不递增 `state_version`，所以「同一任务、同一版本、同一类型」的事件
 * 可以合法地发生多次（崩溃后被重新领取）。把它们折叠成同一个标识，
 * 等于**把「这个任务被领取过几次」这件事从历史里抹掉**——而它恰恰是恢复取证的第一步。
 * 把载荷算进摘要之后，两次领取的载荷（尝试次数不同）必然不同，标识也就不同。
 *
 * 反过来，真正同一次逻辑迁移重放时载荷逐字节相同（判据是
 * [RcaCanonicalDigest] 的规范形式，与序列化实现无关），标识也就相同。
 */
object RcaEventIdFactory {

    /** 与 `chk_rca_domain_event_id` 的 `^[0-9a-f]{32}$` 一致。 */
    private const val EVENT_ID_LENGTH = 32

    fun of(event: RcaDomainEvent): String = RcaCanonicalDigest
        .sha256(
            buildString {
                append(event.aggregateType).append('|')
                append(event.aggregateId).append('|')
                append(event.aggregateVersion).append('|')
                append(event.type.storageCode).append('|')
                // 复用参数摘要的规范形式而不是自己拼字符串：载荷里嵌套的 map 需要
                // 键排序才能稳定，自己拼一个「顺序随调用方写法变化」的字符串，
                // 会让同一次事件在两次重放中得到两个标识，本类存在的意义就没了。
                append(RcaCanonicalDigest.ofParams(event.payload))
            }
        )
        .take(EVENT_ID_LENGTH)
}
