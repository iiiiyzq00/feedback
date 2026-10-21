package com.feedback.repository.impl.rca

import com.feedback.agent.rca.tools.ClusteredSignaturePort
import org.springframework.stereotype.Repository

/**
 * 簇内错误签名分布端口：**本平台没有可读的分布投影**，按契约返回空 Map。
 *
 * ## 这是降级，不是「暂时没写」
 *
 * 已逐表核实过，全库没有「簇 → 错误签名 → 条数」这样的分布事实：
 *
 * - `t_cluster` 只有一个 `error_signature VARCHAR(500)`，是**簇自身**的签名，
 *   不是成员签名的分布；
 * - `t_phase_one_feedback_cluster_member_projection` 是成员投影，**没有签名列**
 *   （列全部为 generation_id / source_id / cluster_id / 渠道 / 地域 / 附件计数 / 决策字段）；
 * - `t_cluster_shadow_candidate_member` 有 `error_signature_hash`，但它是
 *   **影子候选**（隔离评估产物），不是生产簇的成员归属。拿它当生产证据，
 *   等于把一次评估运行的中间结果当成线上事实。
 *
 * 因此不构造一个「大概是这样」的分布。空 Map 是端口契约明确允许的结果，
 * 语义是「该簇尚未固化分布」，下游据此把 `ERROR_SIGNATURE_DISTRIBUTION`
 * 记为**缺口**而不是「分布为空说明只有一种签名」。
 *
 * ## 后果（必须如实记录，不能只写在代码里）
 *
 * `ProblemProfile.multiMember` 为真的 Episode 会生成
 * `ERROR_SIGNATURE_DISTRIBUTION` 强制项，而本实现在任何部署下都无法满足它，
 * 于是**每一个多成员 Episode 的这项门禁都必然以缺口收场**。
 * 这是当前接入的已知边界：多成员联合调查这条路要走通，
 * 前提是问题组织阶段先产出簇内签名分布事实。
 * 本机夹具每个 Episode 只有 1 个 ACTIVE 成员（`multiMember = false`），
 * 所以本条边界在本轮端到端验证中不会被触发。
 *
 * ## 为什么与六类上游降级不放在同一个类里
 *
 * 读者可能觉得两类都是「返回不可用」，合起来少一个文件。但它们的契约位置不同：
 * 六类是**向上游系统取证**（`UpstreamEnvelope` 表达失败），本类是
 * **读本平台问题组织阶段的既有产出**（返回空 Map 表达「未固化」）。
 * 合并会让「空 Map」与「failed 信封」两种不同的降级语义共处一个类，
 * 而将来接上真数据源时，改的只会是这一个类。
 */
@Repository
class RcaClusteredSignatureRepository : ClusteredSignaturePort {

    /**
     * 恒返回空 Map。
     *
     * 不对 `clusterId` 做任何校验或查询：本平台连簇本身都还没有
     * （接入期 `t_rca_agent_task.cluster_id` 为 NULL），
     * 查一个不存在的簇与查一个已存在但未固化的簇，答案都是同一个。
     */
    override fun load(clusterId: Long): Map<String, Long> = emptyMap()
}
