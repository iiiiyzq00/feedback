package com.feedback.agent.rca.replay

import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.graph.EpisodeFacts
import com.feedback.agent.rca.tools.ContentStatusRecord
import com.feedback.agent.rca.tools.CrashAnrRecord
import com.feedback.agent.rca.tools.HistoricalCaseRecord
import com.feedback.agent.rca.tools.LogSpan
import com.feedback.agent.rca.tools.LogTraceRecord
import com.feedback.agent.rca.tools.ReleaseChangeRecord
import com.feedback.agent.rca.tools.ServiceHealthRecord
import com.feedback.agent.rca.tools.ServiceHealthRow
import com.feedback.agent.rca.tools.VersionCohortRecord
import com.feedback.agent.rca.tools.VersionCohortRow
import java.time.LocalDateTime

/**
 * 414 场景的固定夹具。
 *
 * 场景取自失败复盘里的「414 视频鉴权失败」：四条不同表述的反馈——
 * 「课程视频黑屏」「资源加载失败」「鉴权异常」——被组织进同一个联合调查候选。
 *
 * 真实根因是 4.2.0 这次灰度发布把资源鉴权请求的参数改了名（`resourceToken` → `resource_token`）
 * 且没有开启双写兼容开关。旧参数名请求被资源鉴权服务以 HTTP 400
 * `PARAM_MISSING:resourceToken` 拒绝，客户端拿不到播放地址，用户只看到黑屏。
 *
 * 三条证据链上的事实是刻意摆成这样的，缺一条结论就撑不起来：
 * - **客户端没有报错**：Trace 里客户端播放器初始化成功。所以「黑屏是客户端自己的问题」
 *   这条最省事的解释在现场证据面前站不住，故障方向只能往上游走。
 * - **最早失败点在服务端**：资源鉴权服务返回 400，且早于客户端的任何动作。
 * - **变更明细给出机制**：发布记录里的参数改名解释了「为什么会被拒」，
 *   没有它，「参数不兼容」只是一个猜测。
 *
 * 这个场景之所以值得做成固定回放，是因为它同时踩中三类典型错误：
 * 1. **代表日志以偏概全**：查到的日志属于其中一个成员，不能直接证明另外三条；
 *    簇内另一个成员的签名还是 `PLAYER_INIT_TIMEOUT`，与本条 Trace 完全不同。
 * 2. **拿单点证据说范围性结论**：日志只说明这一个 Episode 的现象，
 *    说「集中在新版本」必须另取带分母与对照组的队列证据。
 * 3. **现象点被当成根因**：客户端黑屏是下游表现，最早失败点在服务端鉴权接口，
 *    而变更注入点在客户端发布。
 */
data class ReplayScenario(
    val logTrace: LogTraceRecord,
    val releaseChanges: List<ReleaseChangeRecord>,
    val cohortRecord: VersionCohortRecord,
    val serviceHealth: ServiceHealthRecord,
    val crashAnr: CrashAnrRecord,
    val contentStatus: ContentStatusRecord,
    val historicalCases: HistoricalCaseRecord,
    val clusterSignatureCounts: Map<String, Long>,
    val watermark: LocalDateTime
) {
    companion object {
        const val INVALID_LOG_ID: String = "INVALID_LOG_ID"
        const val INSUFFICIENT_SAMPLE: String = "INSUFFICIENT_SAMPLE"

        // 回放的时间锚点。所有相对时间都从它推出来，保证同一份夹具在任何时刻执行
        // 都得到完全相同的状态与结论。
        val FAULT_AT: LocalDateTime = LocalDateTime.of(2026, 3, 14, 10, 2, 11)

        const val EPISODE_ID: Long = 41401L
        const val TASK_ID: Long = 9140L

        /**
         * 任务的业务幂等键。
         *
         * 与 [TASK_ID] 的区别是「谁在用」：主键是数据库给的行标识，幂等键是外部重投时用来判重的。
         * 注入套件每条用例都要新起一个任务，只能问租约表要 ID，但幂等键必须每次都一样——
         * 换了键就不是同一个任务了，而套件要验的正是「同一个任务从中断处被接管」。
         */
        const val TASK_KEY: String = "rca-task-414"
        const val CLUSTER_ID: Long = 7301L
        const val APP_ID: String = "com.xxx.study"
        const val LOG_ID: String = "LOG-414-0A71C9"
        const val PROBLEM_KEY: String = "course-video-black-screen"
        const val ERROR_SIGNATURE: String = "HTTP_400@/api/resource/auth"
        const val BAD_VERSION: String = "4.2.0"
        const val GOOD_VERSION: String = "4.1.3"

        /** Episode 事实。四条反馈来自 3 个账号、4 台设备，所以影响面按去重后计。 */
        fun episodeFacts(): EpisodeFacts = EpisodeFacts(
            episodeId = EPISODE_ID,
            tenantKey = "DEFAULT",
            appId = APP_ID,
            problemKey = PROBLEM_KEY,
            generation = 1,
            errorSignature = ERROR_SIGNATURE,
            occurredFrom = FAULT_AT.minusMinutes(6),
            occurredTo = FAULT_AT.plusMinutes(4),
            impact = ImpactCounts(
                // 四条反馈、三个账号、四台设备。三个数分列，禁止相加——
                // 相加会得到「影响 11 个用户」这种没有意义的数字。
                repeatCount = 4,
                distinctUserCount = 3,
                distinctDeviceCount = 4
            ),
            crashOrAnr = false,
            hasValidLogId = true,
            logId = LOG_ID,
            // 联合调查候选有 3 个成员，因此必须先看签名分布。
            clusterMemberCount = 3,
            clusterId = CLUSTER_ID,
            affectedVersions = listOf(BAD_VERSION, GOOD_VERSION),
            // 对照组由事实层给出，不由程序自动挑：自动挑会让同一份数据跑出不同结论。
            controlVersion = GOOD_VERSION,
            symptomSummary = "课程视频点击后黑屏，播放器初始化失败；部分用户提示资源加载失败或鉴权异常"
        )

        fun of(): ReplayScenario = ReplayScenario(
            logTrace = logTrace(),
            releaseChanges = releaseChanges(),
            cohortRecord = cohortRecord(),
            serviceHealth = serviceHealth(),
            crashAnr = CrashAnrRecord(emptyList()),
            contentStatus = ContentStatusRecord(
                resourceToken = "RES-VIDEO-88213",
                publishStatus = "PUBLISHED",
                visibility = "ALL_TENANTS",
                cdnStatus = "READY",
                authPolicyId = "auth-policy/v7"
            ),
            historicalCases = HistoricalCaseRecord(emptyList()),
            clusterSignatureCounts = mapOf(
                // 三个成员的错误签名分布。三者指向同一个上游接口，但表述完全不同，
                // 所以只看其中一条代表日志会得到片面的结论。
                "HTTP_400@/api/resource/auth" to 2L,
                "PLAYER_INIT_TIMEOUT@video_play" to 1L
            ),
            // 水位必须晚于**所有**被请求的窗口上界，否则版本对照与发布变更两条工具都会
            // 因为 STALE 而拒绝出证据。取证窗口最宽的一档是强制证据计划的
            // 「现象结束时间 + 30 分钟」，所以这里直接放到故障后两小时，
            // 让回放不依赖具体的窗口配置。
            watermark = FAULT_AT.plusHours(2)
        )

        /**
         * 客户端 Trace。
         *
         * 三个 span 的成败状态是这条证据的全部价值所在：**只有资源鉴权服务失败**。
         * 客户端自身的两个环节（发起播放、播放器初始化）都是成功的，
         * 所以「黑屏是客户端自己的问题」这条解释在现场证据面前站不住。
         *
         * 事件时间上，鉴权 400 出现在 `10:02:09`，早于播放器初始化的 `10:02:11`。
         * 也就是说工具定位到的首错点在服务端，客户端的黑屏是下游表现。
         * 这是「现象点不是根因」的可执行证据。
         */
        private fun logTrace(): LogTraceRecord = LogTraceRecord(
            traceId = "trace-414-0a71c9",
            logId = LOG_ID,
            spans = listOf(
                LogSpan(
                    serviceName = "study-client",
                    operationName = "openCourseVideo",
                    endpoint = "/video/play",
                    startedAt = FAULT_AT.minusSeconds(3),
                    durationMs = 2_400,
                    statusCode = 200
                ),
                LogSpan(
                    serviceName = "resource-auth-service",
                    operationName = "verifyResourceToken",
                    endpoint = "/api/resource/auth",
                    statusCode = 400,
                    startedAt = FAULT_AT.minusSeconds(2),
                    durationMs = 37,
                    // 服务端报的是「缺少 resourceToken」：请求里这个参数不见了。
                    // 这正是客户端改名后服务端看到的现象，也是机制链的第一环。
                    errorCode = "PARAM_MISSING:resourceToken",
                    failed = true
                ),
                // 播放器初始化**成功**：客户端没有崩溃、没有解码错误，
                // 它只是拿不到可播放的地址而显示黑屏。若这里写成初始化失败，
                // 「客户端缺陷」这条错误方向就有了现场支持，场景也就失去了它的教学价值。
                LogSpan(
                    serviceName = "video-player-sdk",
                    operationName = "initialize",
                    endpoint = null,
                    statusCode = 200,
                    startedAt = FAULT_AT,
                    durationMs = 1_800
                )
            )
        )

        /**
         * 发布记录：4.2.0 把资源鉴权请求的参数从 `resourceToken` 改名为 `resource_token`。
         *
         * 这条证据是「机制链」本身——它解释了为什么服务端会报缺少 `resourceToken`。
         * 没有它，「参数改名导致鉴权失败」就只是一个猜测。
         *
         * 两个字段的口径要连起来看：
         * - `component` 是**变更注入点**（客户端），不是失败发生点（资源鉴权服务）。
         *   两者不同是正常的：服务端是被这个请求打挂的，不是它自己坏了。
         * - `compatSwitch = false` 是这条记录最有用的一处——它把「服务端为什么不肯兼容」
         *   变成一个可核对的配置项，也是结论文档里那个待确认的证据缺口。
         *
         * 发布时间取故障前 20 分钟，落在取证窗口内：工具的口径是「窗口内共 N 次发布」，
         * 把发布放到窗口外而仍然被返回，会让这句摘要变成假话，夹具必须自洽。
         */
        private fun releaseChanges(): List<ReleaseChangeRecord> = listOf(
            ReleaseChangeRecord(
                releaseId = "REL-4.2.0-study-client",
                appId = APP_ID,
                component = "study-client",
                version = BAD_VERSION,
                releasedAt = FAULT_AT.minusMinutes(20),
                changeTypes = listOf("API_CONTRACT", "CONFIG"),
                changeSummary = "资源鉴权请求参数改名，未开启双写兼容开关",
                changeDetail = mapOf(
                    "renamedParam" to "resourceToken -> resource_token",
                    "compatSwitch" to "false"
                ),
                rollbackAvailable = true
            )
        )

        /**
         * 版本对照。
         *
         * 分母与对照组都在：4.2.0 会话 12 800 次、失败 1 536 次（失败率 12%）；
         * 对照版本 4.1.3 会话 41 200 次、失败 41 次（失败率约 0.1%）。
         * 正因为有分母，「失败集中在新版本」这句话才成立——
         * 同一份数据只给分子（1536 次失败）是推不出集中度的。
         */
        private fun cohortRecord(): VersionCohortRecord = VersionCohortRecord(
            metricKey = "video_play_failure_rate",
            rows = listOf(
                VersionCohortRow(version = GOOD_VERSION, sessions = 41_200, errors = 41),
                VersionCohortRow(version = BAD_VERSION, sessions = 12_800, errors = 1_536)
            ),
            controlVersion = GOOD_VERSION,
            sampleSufficient = true
        )

        /**
         * 服务健康：**有错误请求，但没有服务级异常窗口**。
         *
         * 这个组合是刻意的，也是这条证据的全部价值所在：
         * - 如果只给「错误请求数」，会被读成「服务挂了」；
         * - 如果只给「没有异常窗口」，又会被读成「什么都没有发生」。
         *
         * 两者同时给出，结论才唯一：失败确实存在（1577 次），但它没有把服务整体打到异常水位，
         * 也就是说失败的分布是**有选择的**——只落在某一部分请求上。
         * 于是排查方向从「资源鉴权服务本身」转向「这些请求为什么不被接受」。
         *
         * 为什么 1577 次错误可以没有异常窗口：异常窗口由上游按该服务自身的历史基线判定，
         * 不是本地按固定阈值算的。该服务平时就有这个量级的失败，因此不构成异常。
         * 这个界线归上游所有，Agent 只消费结论、不自己另算一套阈值——
         * 两套阈值一定会打架，而打架时无法判断该信谁。
         */
        private fun serviceHealth(): ServiceHealthRecord = ServiceHealthRecord(
            rows = listOf(
                ServiceHealthRow(
                    serviceName = "resource-auth-service",
                    totalRequests = 54_000,
                    errorRequests = 1_577,
                    // 无异常窗口：错误量本身不足以判定为服务级异常。
                    anomalyWindowFrom = null,
                    anomalyWindowTo = null,
                    sharedDependencies = listOf("config-center")
                ),
                ServiceHealthRow(
                    serviceName = "video-cdn",
                    totalRequests = 88_000,
                    errorRequests = 0
                )
            ),
            partial = false
        )
    }
}
