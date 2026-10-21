package com.feedback.config

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.graph.AgentInvestigateNode
import com.feedback.agent.rca.graph.BaselineEvidenceNode
import com.feedback.agent.rca.graph.ContextBuildNode
import com.feedback.agent.rca.graph.RcaBudgetPolicy
import com.feedback.agent.rca.graph.EpisodeFactsPort
import com.feedback.agent.rca.graph.RcaPersistNode
import com.feedback.agent.rca.graph.RcaPersistencePort
import com.feedback.agent.rca.graph.RcaStateGraph
import com.feedback.agent.rca.graph.RootCauseArbitrateNode
import com.feedback.agent.rca.model.LlmInvestigationModel
import com.feedback.agent.rca.runtime.ProviderStateQueryPort
import com.feedback.agent.rca.runtime.RcaRecoveryResolver
import com.feedback.agent.rca.runtime.RcaSnapshotJsonCodec
import com.feedback.agent.rca.runtime.RcaTaskLeasePort
import com.feedback.agent.rca.runtime.RcaToolCallJournalPort
import com.feedback.agent.rca.runtime.RcaToolCallJournalWriter
import com.feedback.agent.rca.runtime.RcaWorker
import com.feedback.agent.rca.tools.ClusteredSignaturePort
import com.feedback.agent.rca.tools.ContentStatusPort
import com.feedback.agent.rca.tools.CrashAnrPort
import com.feedback.agent.rca.tools.HistoricalCasePort
import com.feedback.agent.rca.tools.LogTracePort
import com.feedback.agent.rca.tools.ReleaseChangePort
import com.feedback.agent.rca.tools.RcaToolRegistry
import com.feedback.agent.rca.tools.ServiceHealthPort
import com.feedback.agent.rca.tools.ToolInvocationGuard
import com.feedback.agent.rca.tools.VersionCohortPort
import com.feedback.agent.rca.tools.ContentStatusTool
import com.feedback.agent.rca.tools.CrashAnrTool
import com.feedback.agent.rca.tools.HistoricalCaseTool
import com.feedback.agent.rca.tools.LogTraceTool
import com.feedback.agent.rca.tools.ReleaseChangeTool
import com.feedback.agent.rca.tools.ServiceHealthTool
import com.feedback.agent.rca.tools.VersionCohortTool
import com.feedback.service.ai.LlmClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * RCA 运行时的装配（入口侧与执行侧共用）。
 *
 * 与会话化的装配（[RcaEpisodeConfiguration]）分开，是因为两者**生命周期不同**：
 * 会话化的四个 Bean 是纯计算对象，随应用启动即就绪且无副作用；
 * 这里的时钟供接入调度与 Worker 使用，它们的调用时机由调度决定。
 *
 * ## 为什么时钟要显式声明，而不是靠构造参数默认值
 *
 * `RcaClock` 的各个使用点（节点、工具、Worker）都写了 `= RcaClock.system()` 默认值，
 * 那些默认值是为**离线回放**准备的——回放必须注入固定时钟，不能取系统时间。
 * 生产侧则相反：必须显式拿到系统时钟，且只有一处定义。
 * 显式声明一个 Bean，两边就都清楚了：离线 `main()` 永远显式传 `RcaClock.fixed(...)`，
 * Spring 侧永远用这个 Bean。
 *
 * ## 执行侧为什么在这里装配，而不是给每个类加构造型注解
 *
 * 五个节点、七个工具、状态图、Worker 都**刻意不带** `@Component`。它们是为离线回放设计的：
 * `replay/RcaReplayMain.kt` 用固定时钟、脚本化模型与内存持久化把它们 `new` 出来，
 * 断言的是路径与结论而不是装配。给它们加构造型注解会让「离线装配」变成
 * 容器装配的另一种写法，回放就不再能自由替换端口。
 *
 * 所以装配集中在**这一个文件**里，依赖方向是显式的：谁依赖谁一眼能读完。
 * 代价是新增节点要来这里加一行，收益是整条图的形状可审。
 *
 * ## 哪些 Bean 不在这里声明
 *
 * 适配器都由 Spring 自己扫出来，**绝不能在这里再声明一遍**：
 * `RcaTaskLeaseAdapter`（`@Component`，带 `@Transactional`——重新声明成 `@Bean`
 * 会绕过代理，静默地让事务失效）、`MyBatisRcaEpisodeFactsRepositoryImpl`、
 * `MyBatisRcaPersistenceRepositoryImpl`、`MyBatisRcaHistoricalCaseRepositoryImpl`、
 * `RcaClusteredSignatureRepository`、`RcaUpstreamUnavailableRepository`、
 * `MyBatisRcaToolCallJournalRepository`（**它的两个写方法必须各自自动提交**，
 * 被这里声明成 `@Bean` 或在别处包进事务都会让 `PENDING` 行随崩溃一起回滚，
 * 两阶段写入随即失效且不报错）、`RcaProviderStateUnavailableRepository`（都是 `@Repository`），
 * 以及 `RcaIntakeScheduler` / `RcaWorkerScheduler`（`@Component`）。
 * 这里只**消费**它们的端口类型。
 */
@Configuration(proxyBeanMethods = false)
class RcaRuntimeConfiguration {

    /** 生产时钟。回放与测试不走这个 Bean，它们显式传 `RcaClock.fixed`。 */
    @Bean
    fun rcaClock(): RcaClock = RcaClock.system()

    // -----------------------------------------------------------------------
    // 工具层
    // -----------------------------------------------------------------------

    /**
     * 七个只读工具。**必须七个全给**：[RcaToolRegistry] 构造时校验覆盖全部
     * [com.feedback.agent.rca.domain.RcaToolName] 且不重复，漏一个就是启动失败。
     * 这是刻意的：漏注册的工具在运行期表现为「模型选了它却找不到实现」，
     * 而那时已经烧掉了一轮模型调用。
     *
     * 其中六类的端口由 [com.feedback.repository.impl.rca.RcaUpstreamUnavailableRepository]
     * 提供一个「按契约降级」的实现；历史案例那一类接的是本地库上已人工确认的根因。
     *
     * `journal` 是工具调用记录的写入侧：守卫在真实调用前后各写一次
     * （`PENDING` → `COMMITTED`），中间那段就是崩溃窗口。它只对真实调用生效，
     * 复用与预算拒绝都不写——与「复用不产生新证据」是同一条规则。
     */
    @Bean
    fun rcaToolGuard(
        clock: RcaClock,
        logTrace: LogTracePort,
        releaseChange: ReleaseChangePort,
        versionCohort: VersionCohortPort,
        serviceHealth: ServiceHealthPort,
        crashAnr: CrashAnrPort,
        contentStatus: ContentStatusPort,
        historicalCase: HistoricalCasePort,
        journal: RcaToolCallJournalWriter
    ): ToolInvocationGuard = ToolInvocationGuard(
        RcaToolRegistry(
            listOf(
                LogTraceTool(logTrace, clock),
                ReleaseChangeTool(releaseChange, clock),
                VersionCohortTool(versionCohort, clock),
                ServiceHealthTool(serviceHealth, clock),
                CrashAnrTool(crashAnr, clock),
                ContentStatusTool(contentStatus, clock),
                HistoricalCaseTool(historicalCase, clock)
            )
        ),
        journal = journal
    )

    // -----------------------------------------------------------------------
    // 模型端口
    // -----------------------------------------------------------------------

    /**
     * 调查决策模型。
     *
     * 模型名取 `app.ai.llm.model`——与真正发起调用的 `LlmClient` 用**同一个键**。
     * 落库的 `model_version` 必须与线上实际使用的模型一致，各配一份必然漂移。
     *
     * 这个 Bean 的默认值（空串）本身是合法状态：`LlmInvestigationModel` 会把
     * 「未配置」表达成一条 `HUMAN_REQUIRED` 停止提案，而不是启动失败。
     * 未配置模型时整个运行时仍然可以跑完并如实产出「转人工」。
     */
    @Bean
    fun rcaInvestigationModel(
        llm: LlmClient,
        @Value("\${app.ai.llm.model:}") model: String
    ): LlmInvestigationModel = LlmInvestigationModel(llm, model)

    // -----------------------------------------------------------------------
    // 状态图
    // -----------------------------------------------------------------------

    /**
     * 五节点状态图。
     *
     * [ClusteredSignaturePort] 是**唯一一个按契约返回空 Map 的端口**（不是失败信封）：
     * 本平台没有簇内签名分布投影，空 Map 的语义是「分布未固化」。
     * 后果写在 `RcaClusteredSignatureRepository` 的说明里——多成员 Episode 的
     * `ERROR_SIGNATURE_DISTRIBUTION` 强制项在任何部署下都只能以缺口收场。
     *
     * `properties` 只为 [ContextBuildNode] 的预算策略而来：截止时间取
     * `app.rca.task-timeout-minutes`，与 `claim` 写进 `t_rca_agent_task.deadline_at`
     * 的是同一个值。离线装配不走这里，用的是 `RcaBudgetPolicy.DEFAULT`。
     */
    @Bean
    fun rcaStateGraph(
        clock: RcaClock,
        facts: EpisodeFactsPort,
        guard: ToolInvocationGuard,
        signatures: ClusteredSignaturePort,
        model: LlmInvestigationModel,
        persistence: RcaPersistencePort,
        properties: RcaProperties
    ): RcaStateGraph = RcaStateGraph(
        listOf(
            ContextBuildNode(facts, RcaBudgetPolicy.production(properties.taskTimeoutMinutes)),
            BaselineEvidenceNode(guard, signatures),
            AgentInvestigateNode(model, guard, clock = clock),
            RootCauseArbitrateNode(clock),
            RcaPersistNode(persistence)
        )
    )

    // -----------------------------------------------------------------------
    // 恢复判定
    // -----------------------------------------------------------------------

    /**
     * 领取任务后的恢复判定入口。
     *
     * 两个依赖都来自**同一个适配器**
     * （[com.feedback.repository.impl.rca.MyBatisRcaToolCallJournalRepository]
     * 同时实现 [RcaToolCallJournalPort] 与 [RcaToolCallJournalWriter]）。
     * 两个接口类型注进来的必然是同一个单例——这一点很重要：判定看到的记录，
     * 与边界写下的记录，必须是同一套编解码口径，否则「写进去的」和「读出来的」
     * 会是两个不同的事实，而且不会报错。
     *
     * `provider` 目前是一个如实回答「问不到」的实现
     * （[com.feedback.repository.impl.rca.RcaProviderStateUnavailableRepository]），
     * 因此「已发出、带请求 ID、未落定」的那一类调用会转人工而不是被自动重放。
     * 这是保守方向的选择，理由写在该适配器的说明里。
     */
    @Bean
    fun rcaRecoveryResolver(
        journal: RcaToolCallJournalPort,
        provider: ProviderStateQueryPort
    ): RcaRecoveryResolver = RcaRecoveryResolver(journal, provider)

    /**
     * 任务帧。
     *
     * `recovery` 现在注入真实实现。此前它是默认的 `RcaRecoveryResolver.NONE`，
     * 语义是「这一层没有信息」——在 `t_rca_tool_call` 没有写入侧时那是**准确**的描述，
     * 但写入侧接上之后就不准确了：记录表有了、在途调用看得见了，
     * 再用 `NONE` 等于把「有一条调用无法判定」放行成「没有已知的在途调用」。
     *
     * ## 为什么这里必须带条件装配（2026-10-09 修）
     *
     * 这个 Bean 以前是**无条件**的，而 [RcaWorker] 的 `init` 会
     * `require(workerId.isNotBlank())`，于是 `app.rca.worker-id` 默认为空串这件事
     * 让**整个应用起不来**——即使 `app.rca.worker-enabled` 与 `xxl.job.enabled`
     * 两层开关都是关的。实测现象是 `bootRun` 直接
     * `Failed to instantiate [RcaWorker]: Worker 标识不能为空`。
     *
     * 为什么测试没发现：全仓 `@SpringBootTest` 都带
     * `spring.main.lazy-initialization=true`（重上下文的既有约定），懒装配下没人注入
     * 它就不会被创建；生产是要预先实例化单例的，于是必炸。
     * 两个既有套件（`MqTrackEndToEndTest`、`TenantIsolationEndToEndTest`）此前是
     * 在属性里显式填一个 `app.rca.worker-id` 绕过去的，注释写的就是这条构造期校验。
     *
     * 修法定的是：**功能关着，它的 Bean 就不该存在**。开关打开而 `worker-id` 仍为空时
     * 依旧在这里失败（[RcaWorker] 的 `require` 是刻意的安全不变量：空标识会让 fencing
     * 退化成 owner 匹配），但那时操作者确实显式要求跑这个 worker，快速失败是对的；
     * `RcaProperties.logReadiness` 会在此之前先打一行
     * `ready=false, errors=[RCA_WORKER_ID_MISSING]`，
     * 所以落到眼里的是「为什么」而不是只有一个 `IllegalArgumentException`。
     *
     * 连带改动：`RcaWorkerScheduler` 不能对 [RcaWorker] 声明硬依赖（那只是把同一个失败
     * 挪到它自己的构造上，生产上调度框架会在启动时枚举 `@JobHandler` 强制实例化它），
     * 所以它改成 `ObjectProvider<RcaWorker>`，开关关着时**空转**而不是缺席。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.rca", name = ["worker-enabled"], havingValue = "true")
    fun rcaWorker(
        leases: RcaTaskLeasePort,
        graph: RcaStateGraph,
        clock: RcaClock,
        properties: RcaProperties,
        recovery: RcaRecoveryResolver
    ): RcaWorker = RcaWorker(
        leases = leases,
        graph = graph,
        codec = RcaSnapshotJsonCodec(),
        clock = clock,
        workerId = properties.workerId,
        leaseSeconds = properties.leaseSeconds,
        recovery = recovery
    )
}
