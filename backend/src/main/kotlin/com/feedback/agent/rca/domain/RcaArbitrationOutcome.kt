package com.feedback.agent.rca.domain

/**
 * 仲裁节点的产出，作为共享状态的一块随快照落库。
 *
 * 为什么放在共享状态里而不是节点之间的进程内传递：共享状态是**崩溃恢复的最小快照单位**。
 * 归因一旦只存在于进程内存中，任务在「仲裁完成、尚未落库」这个窗口崩溃后恢复，
 * 就再也拿不到已算出的归因，只能重新仲裁——对纯函数来说结果一样，但
 * 「状态之外还有隐藏输入」这件事本身会让恢复路径难以推理。
 * 放进状态后，恢复只需读快照，不需要任何额外的外部约定。
 *
 * [violationCode] 存的是检查违规类型的落库编码而不是枚举：领域层不应该依赖
 * 图编排层的枚举，而且这个值要落进 `t_rca_agent_task.stop_reason` 附近的审计字段，
 * 用字符串编码反而更贴近它的用途。
 */
data class RcaArbitrationOutcome(
    val attribution: EpisodeAttribution,
    /** 逐对引用校验结果，全部落库供「引用支持正确率」复算。 */
    val claimPairs: List<ClaimEvidencePair>,
    /** 违规类型编码；为 null 表示四项检查全过。回退额度用尽时这里仍可能非空。 */
    val violationCode: String?,
    /** 本次仲裁裁决出的任务终态。 */
    val finalStatus: RcaTaskStatus,
    val stopReason: RcaStopReason
) {
    /** 是否允许自动关联 Incident。规则由归因等级决定，不在这里重写。 */
    val autoLinkPermitted: Boolean get() = attribution.level.autoLinkPermitted

    /** 引用支持正确率；无配对时返回 null，避免除零造出一个假的 100%。 */
    val supportRatio: Double?
        get() = if (claimPairs.isEmpty()) {
            null
        } else {
            claimPairs.count { it.verdict == ClaimSupportVerdict.SUPPORTED }.toDouble() / claimPairs.size
        }
}
