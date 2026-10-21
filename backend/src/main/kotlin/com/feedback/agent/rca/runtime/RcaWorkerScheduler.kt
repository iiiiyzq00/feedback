package com.feedback.agent.rca.runtime

import com.feedback.config.RcaProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 执行侧调度：反复调 [RcaWorker.runOnce] 直到队列里没有可领取的任务。
 *
 * ## 这是「待执行任务 → 终态 + 落库」的生产入口
 *
 * 在本类之前，`RcaWorker` 的生产调用方是 **0 个**——它只被离线套件
 * （`replay/RcaReplayMain.kt` 与注入测试）直接 `new` 出来过。
 * 本类接的就是那一段。
 *
 * ## 绝不先 claim 再交给 Worker
 *
 * [RcaWorker.runOnce] **自己**调 `leases.claim(...)`（`runtime/RcaWorker.kt`）。
 * 调用方若先领一次再传进去，就会出现「领了两次、第二次必然 LostRace」，
 * 或者更糟——把第一次的租约令牌丢掉，让 fencing 退化成 owner 匹配。
 * 所以本类只做循环，不碰租约。
 *
 * ## 开关在这一层，不在 handler
 *
 * 与 [com.feedback.agent.rca.intake.RcaIntakeScheduler] 和
 * `service/ai/AiTaskPipeline.kt` 的 `AiWorkerScheduler` 是同一个范式：
 * handler 是薄适配器，**开关在 Scheduler**。`xxl.job.enabled` 关一层、`app.rca.worker-enabled`
 * 再关一层，任一层关闭都不会真正跑。
 *
 * ## 为什么 worker 走 Provider，而不是直接注入（2026-10-09 修）
 *
 * [RcaWorker] 这个 Bean 现在**只在 `app.rca.worker-enabled=true` 时存在**
 * （见 `config/RcaRuntimeConfiguration.kt` 的 `rcaWorker` 及其 KDoc）。
 * 根因是它的 `init` 会 `require(workerId.isNotBlank())`，而 `app.rca.worker-id`
 * 的默认值是空串：只要那个 Bean 被创建，「没配 worker 标识」的部署就整个起不来，
 * 哪怕两层开关都是关的。
 *
 * 所以本类不能声明成对 [RcaWorker] 的**硬依赖**——那只是把失败从 `@Bean` 方法挪到
 * 本类的构造上，症状一样（生产上调度框架会在启动时枚举 `@JobHandler` Bean，
 * 强制实例化本类）。Provider 让「执行侧在不在」在**调用时**回答：
 * 开关关着时正确的结果是**空转**，不是报错。
 *
 * 这么做也保住了 `job/rca/RcaJobHandlers.kt` 开头那条约定：handler 照常装配，
 * 「开关关着」表现为调度被触发但什么都不做。若改成给本类与 handler 一起加
 * `app.rca.worker-enabled` 条件，那组合法配置（调度开着、功能关着）就会从
 * 「空转」变成调度平台侧的一条「handler 不存在」错误。
 *
 * ## 就绪检查为什么还留着（并更正一条以前写错的说法）
 *
 * 旧注释说「Bean 已经建好了，所以空 worker-id 不会在启动时暴露」——**事实相反**。
 * 校验是**构造期**的：`RcaWorker` 一被创建就抛 `IllegalArgumentException`，
 * Spring 包成 `BeanCreationException`；`spring.main.lazy-initialization=true`
 * 也救不了，它推迟的是单例的**创建**。2026-10-09 实测（两个开关全关）：
 * `bootRun` 直接 `Failed to instantiate [RcaWorker]: Worker 标识不能为空`，
 * 而全仓测试仍绿——惰性装配下没有任何人去拿那个 Bean。
 *
 * 更正之后两道检查的分工是清楚的：[runPending] 的 `!workerEnabled` 早退与这里的
 * 就绪检查管「建出来了该不该跑」，装配条件管「Bean 建不建得出来」。
 * 前者仍是**离线装配**（`replay/RcaReplayMain.kt` 这类入口）与直接 `new` 出本类的测试的唯一防线——
 * 那些路径绕过 `@Bean` 方法，空的 worker-id 仍只会在第一次 `runOnce` 时炸。
 */
@Component
class RcaWorkerScheduler(
    private val workerProvider: ObjectProvider<RcaWorker>,
    private val properties: RcaProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 跑一批任务。
     *
     * 循环到 [RcaWorkerRun.Idle] 为止，或达到 [RcaProperties.workerMaxTasksPerRun] 上限。
     *
     * **只有 Idle 会终止循环。** 其余每种结果——包括 `Discarded`（租约丢失）与
     * `Abandoned`（收口成非正常终态）——都意味着**这一个任务**已经处理完，
     * 但队列里可能还有下一个。把 `Discarded` 也当成终止条件会让一个被抢占的任务
     * 卡住后面整批任务；反过来，不设上限则可能在任务持续到达时永不返回。
     *
     * 一次 `runOnce` 抛出的异常不会中断整批：`RcaWorker` 已经把执行异常收口成
     * `PendingRetry` / `Abandoned` 两种返回值，能漏到这里的是**更外层**的失败
     * （例如数据库连接断了）。此时继续领下一个任务只会重复失败，所以记 ERROR 后立即停止。
     */
    fun runPending() {
        if (!properties.workerEnabled) return
        val worker = workerProvider.getIfAvailable()
        if (worker == null) {
            // 开关为 true 时 `rcaWorker` 的条件必然满足，所以走到这里说明装配不一致
            // （例如有人手工组了一个只带本类的上下文）。记 ERROR 而不是静默返回：
            // 静默返回的表现是「调度一直在跑、队列一直不动」，那比一条错误难查得多。
            log.error("RCA 执行开关为 true 但 RcaWorker 装配缺席，本次调度跳过")
            return
        }
        val errors = properties.readinessErrors()
        if (errors.isNotEmpty()) {
            log.error("RCA 执行配置未就绪，本次调度跳过：errors={}", errors)
            return
        }

        var processed = 0
        var finished = 0
        var unsettled = 0
        val limit = properties.workerMaxTasksPerRun

        while (processed < limit) {
            val run = try {
                worker.runOnce()
            } catch (error: Exception) {
                // 到这一层说明不是「单个任务失败」而是执行环境坏了。继续只会重复失败。
                log.error("RCA worker 执行中断：已处理 {} 个任务，本轮停止", processed, error)
                break
            }
            if (run is RcaWorkerRun.Idle) break
            processed++
            when (run) {
                is RcaWorkerRun.Finished -> finished++
                // 租约丢失、被 fencing 拒绝、让出重试、收口——都不算「完成」，
                // 但也不再由本 Worker 负责。分开计数是为了让日志里能看出
                // 「跑了 10 个、成了 3 个」和「跑了 10 个、成了 10 个」的区别。
                else -> unsettled++
            }
        }

        log.info("RCA 执行完成：processed={} finished={} unsettled={} limit={}", processed, finished, unsettled, limit)
    }
}
