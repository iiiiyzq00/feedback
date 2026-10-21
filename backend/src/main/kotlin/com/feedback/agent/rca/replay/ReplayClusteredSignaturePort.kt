package com.feedback.agent.rca.replay

import com.feedback.agent.rca.graph.EpisodeFacts
import com.feedback.agent.rca.tools.ClusteredSignaturePort

/**
 * 回放用的簇内签名分布投影：不走上游，读问题组织层的既有产出。
 *
 * 与 [ReplayUpstreamPorts] 分开成两个类，是因为它们在**契约上本来就不是一类**：
 * 前者实现的是七类只读工具的上游端口，后者读的是归簇阶段已经算好的分布
 * （见 [ClusteredSignaturePort] 自己的说明）。
 * 合成一个类在 Kotlin 里也走不通——两个 `load(Long)` 返回值不同，无法共存。
 *
 * 仍然记进同一个 [ReplayCallRecorder]：取证路径是一条序列，
 * 签名分布与七类工具的调用出现在同一条路径上，分开记就看不出它们之间的先后。
 */
class ReplayClusteredSignaturePort(
    private val facts: EpisodeFacts,
    private val scenario: ReplayScenario,
    private val recorder: ReplayCallRecorder = ReplayCallRecorder()
) : ClusteredSignaturePort {

    override fun load(clusterId: Long): Map<String, Long> {
        recorder.record("CLUSTER_SIGNATURE(clusterId=$clusterId)")
        // 簇 ID 对不上说明编排把别的对象的 ID 用进来了。夹具此时返回空分布，
        // 让下游按「分布为空」处理并失败，而不是悄悄拿出另一簇的分布当作本簇的证据。
        return if (clusterId == facts.clusterId) scenario.clusterSignatureCounts else emptyMap()
    }
}
