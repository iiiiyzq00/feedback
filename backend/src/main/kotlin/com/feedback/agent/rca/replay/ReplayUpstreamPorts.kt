package com.feedback.agent.rca.replay

import com.feedback.agent.rca.graph.EpisodeFacts
import com.feedback.agent.rca.graph.EpisodeFactsPort
import com.feedback.agent.rca.tools.ContentStatusPort
import com.feedback.agent.rca.tools.ContentStatusQuery
import com.feedback.agent.rca.tools.ContentStatusRecord
import com.feedback.agent.rca.tools.CrashAnrPort
import com.feedback.agent.rca.tools.CrashAnrQuery
import com.feedback.agent.rca.tools.CrashAnrRecord
import com.feedback.agent.rca.tools.HistoricalCasePort
import com.feedback.agent.rca.tools.HistoricalCaseQuery
import com.feedback.agent.rca.tools.HistoricalCaseRecord
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

/**
 * 上游与投影端口调用的留痕。
 *
 * 单独成一个类而不是各自记一份，是因为「取证路径」是一条序列：
 * 簇内签名分布虽然不走上游，但它与七类工具调用出现在同一条路径上，
 * 分开记就得在两处各断言一次，还看不出它们之间的先后。
 */
class ReplayCallRecorder {

    private val recorded: MutableList<String> = mutableListOf()

    /** 调用留痕：端口名 + 关键入参。用于断言取证路径而不只是结论。 */
    val calls: List<String> get() = recorded.toList()

    fun record(call: String) {
        recorded += call
    }
}

/**
 * 回放用的上游端口实现：全部由固定夹具回答，不发任何网络请求。
 *
 * 这一层是「固定回放」能成立的关键。上游如果参与，回放就会随上游数据漂移，
 * 那么「改了编排逻辑之后结论是否变化」这个问题就永远无法回答——
 * 你分不清变化是来自自己的改动还是来自上游。所以回放夹具把上游整体替换掉，
 * 只保留**真实的结构**（同样的字段、同样的水位、同样的失败码），不保留真实数据源。
 *
 * 每个端口都记下自己被问过什么，便于断言「这一轮确实去取了这类证据」，
 * 而不是只看最终结论对不对。只断言结论会漏掉「用错工具但碰巧结论一样」这种情况。
 *
 * 簇内签名分布**不在这里**：它不是上游端口（见 [com.feedback.agent.rca.tools.ClusteredSignaturePort]），
 * 而且两个 `load(Long)` 在同一个类里会因返回值不同而无法共存。
 */
class ReplayUpstreamPorts(
    private val facts: EpisodeFacts,
    private val scenario: ReplayScenario,
    private val recorder: ReplayCallRecorder = ReplayCallRecorder()
) : EpisodeFactsPort, LogTracePort, ReleaseChangePort, VersionCohortPort,
    ServiceHealthPort, CrashAnrPort, ContentStatusPort, HistoricalCasePort {

    /** 调用留痕：端口名 + 关键入参。用于断言取证路径而不只是结论。 */
    val calls: List<String> get() = recorder.calls

    // -----------------------------------------------------------------------
    // 事实来源
    // -----------------------------------------------------------------------

    override fun load(episodeId: Long): EpisodeFacts? = facts.takeIf { it.episodeId == episodeId }

    // -----------------------------------------------------------------------
    // 1. 日志 Trace
    // -----------------------------------------------------------------------

    override fun fetch(query: LogTraceQuery): UpstreamEnvelope<LogTraceRecord> {
        recorder.record("LOG_TRACE(logId=${query.logId})")
        if (query.logId != facts.logId) {
            // 回放里出现非常规日志 ID，说明编排把别的对象的 ID 用进来了。
            return UpstreamEnvelope.failed(ReplayScenario.INVALID_LOG_ID, providerRequestId = PROVIDER_LOG)
        }
        return UpstreamEnvelope.ok(scenario.logTrace, PROVIDER_LOG, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 2. 发布变更
    // -----------------------------------------------------------------------

    override fun fetch(query: ReleaseChangeQuery): UpstreamEnvelope<List<ReleaseChangeRecord>> {
        recorder.record("RELEASE_CHANGE(appId=${query.appId}, window=${query.windowFrom}~${query.windowTo})")
        // 按窗口过滤是**上游**的职责，工具只是把窗口透传下来。夹具也必须照做，
        // 否则「窗口内共 N 次发布」这句摘要会包含窗口外的发布，夹具就把工具的说法变成了假话。
        // 顺带一个好处：时间窗写错时返回空列表，工具会判 NOT_FOUND 并把它记成反证，
        // 回放会立刻失败，而不是悄悄多出一条不该有的证据。
        val inWindow = scenario.releaseChanges.filter { record ->
            !record.releasedAt.isBefore(query.windowFrom) && record.releasedAt.isBefore(query.windowTo)
        }
        val filtered = query.component?.let { component -> inWindow.filter { it.component == component } } ?: inWindow
        return UpstreamEnvelope.ok(filtered, PROVIDER_RELEASE, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 3. 版本对照
    // -----------------------------------------------------------------------

    override fun fetch(query: VersionCohortQuery): UpstreamEnvelope<VersionCohortRecord> {
        recorder.record("VERSION_COHORT(control=${query.controlVersion})")
        if (query.controlVersion != scenario.cohortRecord.controlVersion) {
            // 对照组版本是回放的关键入参：给错了就会拿一个不存在的版本做对照，
            // 而对照错位会让「集中在新版本」这个结论变得不可比。这里按上游口径拒绝。
            return UpstreamEnvelope.failed(ReplayScenario.INSUFFICIENT_SAMPLE, providerRequestId = PROVIDER_COHORT)
        }
        return UpstreamEnvelope.ok(scenario.cohortRecord, PROVIDER_COHORT, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 4. 服务健康
    // -----------------------------------------------------------------------

    override fun fetch(query: ServiceHealthQuery): UpstreamEnvelope<ServiceHealthRecord> {
        recorder.record("SERVICE_HEALTH(services=${query.services.joinToString("/")})")
        return UpstreamEnvelope.ok(scenario.serviceHealth, PROVIDER_HEALTH, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 5. 崩溃 / ANR
    // -----------------------------------------------------------------------

    override fun fetch(query: CrashAnrQuery): UpstreamEnvelope<CrashAnrRecord> {
        recorder.record("CRASH_ANR(appId=${query.appId}, version=${query.version})")
        return UpstreamEnvelope.ok(scenario.crashAnr, PROVIDER_CRASH, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 6. 内容状态
    // -----------------------------------------------------------------------

    override fun fetch(query: ContentStatusQuery): UpstreamEnvelope<ContentStatusRecord> {
        recorder.record("CONTENT_STATUS(token=${query.resourceToken})")
        return UpstreamEnvelope.ok(scenario.contentStatus, PROVIDER_CONTENT, scenario.watermark)
    }

    // -----------------------------------------------------------------------
    // 7. 历史案例
    // -----------------------------------------------------------------------

    override fun fetch(query: HistoricalCaseQuery): UpstreamEnvelope<HistoricalCaseRecord> {
        recorder.record("HISTORICAL_CASE(hint=${query.signatureHint})")
        return UpstreamEnvelope.ok(scenario.historicalCases, PROVIDER_CASE, scenario.watermark)
    }

    private companion object {
        const val PROVIDER_LOG = "rp-log-1"
        const val PROVIDER_RELEASE = "rp-release-1"
        const val PROVIDER_COHORT = "rp-cohort-1"
        const val PROVIDER_HEALTH = "rp-health-1"
        const val PROVIDER_CRASH = "rp-crash-1"
        const val PROVIDER_CONTENT = "rp-content-1"
        const val PROVIDER_CASE = "rp-case-1"
    }
}
