package com.feedback.repository.impl.rca

import com.feedback.agent.rca.tools.ContentStatusPort
import com.feedback.agent.rca.tools.ContentStatusQuery
import com.feedback.agent.rca.tools.ContentStatusRecord
import com.feedback.agent.rca.tools.CrashAnrPort
import com.feedback.agent.rca.tools.CrashAnrQuery
import com.feedback.agent.rca.tools.CrashAnrRecord
import com.feedback.agent.rca.tools.LogTracePort
import com.feedback.agent.rca.tools.LogTraceQuery
import com.feedback.agent.rca.tools.LogTraceRecord
import com.feedback.agent.rca.tools.ReleaseChangePort
import com.feedback.agent.rca.tools.ReleaseChangeQuery
import com.feedback.agent.rca.tools.ReleaseChangeRecord
import com.feedback.agent.rca.tools.ServiceHealthPort
import com.feedback.agent.rca.tools.ServiceHealthQuery
import com.feedback.agent.rca.tools.ServiceHealthRecord
import com.feedback.agent.rca.tools.UpstreamEnvelope
import com.feedback.agent.rca.tools.VersionCohortPort
import com.feedback.agent.rca.tools.VersionCohortQuery
import com.feedback.agent.rca.tools.VersionCohortRecord
import org.springframework.stereotype.Repository

/**
 * 六类**没有真实数据落点**的上游端口，统一按契约降级。
 *
 * ## 为什么是六个而不是七个
 *
 * 七类工具端口里，`HistoricalCasePort` 有真实来源
 * （`t_root_cause_analysis` 上已人工确认的根因，见
 * [MyBatisRcaHistoricalCaseRepositoryImpl]），所以它不在这里。
 * 剩下六类在本仓库**确实没有数据落点**，逐条已核：
 *
 * | 端口 | 为什么接不到真数据 |
 * |---|---|
 * | `LogTracePort` | 全库没有 log_id / trace_id 列，拿不到可查询的日志标识 |
 * | `ReleaseChangePort` | 没有发布变更/组件版本变更表 |
 * | `VersionCohortPort` | 没有按版本分的会话量与错误量事实表（分母无处可取） |
 * | `ServiceHealthPort` | 没有 per-service 的请求量与错误量表 |
 * | `CrashAnrPort` | 没有崩溃聚合指纹表，且符号表不可用 |
 * | `ContentStatusPort` | 没有内容发布/CDN 分发状态表 |
 *
 * ## 降级为什么必须是这个形状
 *
 * 返回 `UpstreamEnvelope.failed(UPSTREAM_UNCONFIGURED)`。这个码**不在**
 * `ReadOnlyRcaTool.UPSTREAM_ERROR_MAPPING` 里，于是按该映射表的保守默认值落到
 * `ToolResultStatus.UNKNOWN`（`evidenceGap = true`）。
 *
 * 两个选择都是刻意的：
 *
 * 1. **不抛异常。** 端口契约要求失败用响应体表达。抛异常会丢掉失败语义，
 *    也会让预算记账漏掉这次调用，而「这次没记账」在账本上看起来跟「没调用过」一样。
 * 2. **不用 `RESOURCE_MISSING`。** 它在 `ToolResultStatus` 里 `evidenceGap = false`，
 *    即「确认不存在」。用它会把「我们还没接入这个数据源」说成「上游确认没有这类数据」，
 *    下游据此得到一个**有依据的反证**——这是本系统最不该发生的一类错误：
 *    缺证据被当成反证据。
 *
 * ## 一个类实现六个端口
 *
 * 六个 `fetch` 的参数类型互不相同，是重载而非覆盖冲突。
 * `ClusteredSignaturePort` 不能并进来：它的 `load(Long)` 与这里任何一个都不重名，
 * 但它在语义上属于另一个端口族（读本地投影，不是向上游取证），
 * 因此单独成类 [RcaClusteredSignatureRepository]。
 */
@Repository
class RcaUpstreamUnavailableRepository :
    LogTracePort,
    ReleaseChangePort,
    VersionCohortPort,
    ServiceHealthPort,
    CrashAnrPort,
    ContentStatusPort {

    override fun fetch(query: LogTraceQuery): UpstreamEnvelope<LogTraceRecord> = unavailable()

    override fun fetch(query: ReleaseChangeQuery): UpstreamEnvelope<List<ReleaseChangeRecord>> = unavailable()

    override fun fetch(query: VersionCohortQuery): UpstreamEnvelope<VersionCohortRecord> = unavailable()

    override fun fetch(query: ServiceHealthQuery): UpstreamEnvelope<ServiceHealthRecord> = unavailable()

    override fun fetch(query: CrashAnrQuery): UpstreamEnvelope<CrashAnrRecord> = unavailable()

    override fun fetch(query: ContentStatusQuery): UpstreamEnvelope<ContentStatusRecord> = unavailable()

    /**
     * 统一的失败响应。
     *
     * 不记录调用日志：本类的每一次调用都得到同一个结果，逐次打日志只会淹掉
     * 真正需要看的行。调用次数由工具层的记账负责，那里记的是**预算**，
     * 才是需要精确的信息。
     */
    private fun <T> unavailable(): UpstreamEnvelope<T> =
        UpstreamEnvelope.failed(UPSTREAM_UNCONFIGURED)

    private companion object {
        /**
         * 未接入的上游。刻意不在 `ReadOnlyRcaTool.UPSTREAM_ERROR_MAPPING` 里新增映射，
         * 让它落到 UNKNOWN：这个码表达的是**本平台的部署状态**，不是上游给的语义，
         * 混进那张表会让「上游真的返回了 UNKNOWN」与「我们没接」在日志里再也分不开。
         */
        const val UPSTREAM_UNCONFIGURED = "UPSTREAM_UNCONFIGURED"
    }
}
