package com.feedback.repository.impl.rca

import com.feedback.agent.rca.runtime.ProviderFinalState
import com.feedback.agent.rca.runtime.ProviderStateQueryPort
import org.springframework.stereotype.Repository

/**
 * 上游状态查询端口的**未接入**实现：一律回答 [ProviderFinalState.UNREACHABLE]。
 *
 * 类名用 `Repository` 而不是 `Adapter`：本仓库的
 * [RepositoryComplexityGateTest] 要求 `@Repository` 构造型类的类名以
 * `Repository` / `RepositoryImpl` 结尾，同类的降级适配器
 * （[RcaUpstreamUnavailableRepository]、[RcaClusteredSignatureRepository]）也都是这个后缀。
 * 后缀在这里表达的是「数据访问层的组件」，不代表它真的读写数据库——这一点
 * 下面这段说明必须说清楚，否则名字会误导人以为这里有一次查询。
 *
 * ## 为什么要有这个类，而不是继续用 `RcaRecoveryResolver.NONE`
 *
 * `NONE` 的语义是「**这一层没有可用的信息**」——记录表都没有，所以无从判定，
 * 只能放行。在 `t_rca_tool_call` 的写入侧接上之后，这个语义就不再成立了：
 * 记录表有了、在途调用看得见了，此时再用 `NONE` 等于把「有一条调用无法判定」
 * 悄悄放行成「没有已知的在途调用」。
 *
 * 换句话说：`NONE` 不是「默认安全」，它只是「没有信息」。现在有信息了，
 * 就必须换成真实判定，哪怕判定能力本身还是零。
 *
 * ## 为什么是 `UNREACHABLE` 而不是 `NOT_SUPPORTED`
 *
 * 这两个值在 [com.feedback.agent.rca.runtime.RcaToolRecovery.afterReconcile] 里
 * **通向完全不同的处置**：
 *
 * - `NOT_SUPPORTED`（上游明确表态「我不提供这种对账能力」）→ 安全重放；
 * - `UNREACHABLE`（这次问不到）→ **转人工**，不得按未执行处理。
 *
 * 本仓库没有任何上游系统提供「按请求 ID 查最终状态」的接口：这不是上游表态不支持，
 * 是我们**还没接**。选 `NOT_SUPPORTED` 会让每一次「已发出但未落定」的调用都被
 * 自动重放，而重放的正当理由（「上游确认不会执行第二次」）在这条路径上根本没有依据。
 * 选 `UNREACHABLE` 则让它转人工——代价是人工核对，收益是不会把一次可能已经执行的
 * 调用当成没发生过。
 *
 * ## 这条路径在生产上有多常见
 *
 * 少见，但不是死代码：写入侧只在 `begin`（`provider_request_id` 恒空）与 `commit`
 * （同一句里写 `COMMITTED`）两处动这张表，所以带请求 ID 却仍是 `PENDING` 的行
 * 只能由部分写入产生——例如回写语句本身失败。真出现时，它的正确处置恰好是转人工，
 * 而这条实现给出的正是这个答案。
 */
@Repository
class RcaProviderStateUnavailableRepository : ProviderStateQueryPort {

    /** 本仓库没有可供对账的上游接口，因此诚实的回答是「这次问不到」。 */
    override fun queryFinalState(providerRequestId: String): ProviderFinalState =
        ProviderFinalState.UNREACHABLE
}
