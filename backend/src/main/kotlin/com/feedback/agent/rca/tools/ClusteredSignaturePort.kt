package com.feedback.agent.rca.tools

/**
 * 簇内错误签名分布端口。
 *
 * 刻意**不是**七类 Agent 工具之一：这个分布是「问题组织」阶段的既有产出，
 * 在联合调查候选形成时就已经固化。把它包装成一个 Agent 工具会让
 * 「Agent 只通过只读工具取证」这条边界失去意义——那本质上是读自己平台的一张投影表，
 * 不是向上游系统取证。
 *
 * 但它是强制证据项：多成员簇必须先看签名分布，确认成员是否真的同因，
 * 否则单条代表日志会以偏概全。所以它由 BASELINE_EVIDENCE 节点直接读取，
 * 产出的证据覆盖粒度是 CLUSTER 级。
 */
interface ClusteredSignaturePort {
    /**
     * 返回簇内错误签名到条数的映射。
     *
     * 返回空 Map 表示该簇没有已固化的分布（尚未组织完成），此时强制项记为缺口，
     * 而不是「分布为空说明只有一种签名」。
     */
    fun load(clusterId: Long): Map<String, Long>
}
