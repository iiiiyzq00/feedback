package com.feedback.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * RCA 生产入口配置。
 *
 * 这里集中两件事：**接入侧的批量与数据环境**、**执行侧的 worker 身份与租约**。
 * 两侧各有一个默认关闭的开关，与 `xxl.job.enabled`（`job/EnabledXxlJob.kt`）构成双开关——
 * 调度平台先关一层，部署配置再关一层，两层都打开才会真正跑。
 *
 * ## 为什么数据环境要进配置
 *
 * `t_feedback_source.data_environment` 由 V115 建立，并有 `chk_source_fixture_tag` 保证
 * `TEST`/`VERIFICATION` 行必须带 `fixture_tag`。接入把它变成查询条件而不是过滤代码，
 * 于是「夹具数据不会进生产证据账本」是**查询层面**的性质：默认 `PRODUCTION` 时，
 * 夹具行根本不会被选出，不需要任何一处代码记得去过滤。
 *
 * 本机验证时把它设成 `VERIFICATION`，读的就是那批带 `fixture_tag` 的合成夹具。
 */
@Component
@ConfigurationProperties(prefix = "app.rca")
class RcaProperties {

    /** 接入侧开关。默认关闭；打开后 `feedbackRcaIntakeHandler` 才会真正扫来源表。 */
    var intakeEnabled: Boolean = false

    /** 执行侧开关。默认关闭；打开后 `feedbackRcaWorkerHandler` 才会真正领取任务。 */
    var workerEnabled: Boolean = false

    /**
     * 单次接入查询的来源记录上限。
     *
     * 与 `AiPipelineRepository.enqueueSourceIds` 同样是「游标 + 批量上限」，但上限更宽，
     * 因为接入一条信号只写四行、不做模型调用。
     */
    var intakeBatchSize: Int = 100

    /**
     * 只接入该数据环境的来源记录。
     *
     * 取值与 `chk_source_data_environment` 一致：`PRODUCTION` / `TEST` / `VERIFICATION`。
     * **默认 `PRODUCTION`，即夹具与验证数据默认不进生产账本。**
     */
    var intakeDataEnvironment: String = "PRODUCTION"

    /** 单次接入任务的批量轮数上限，防止调度入口形成无界循环。 */
    var intakeMaxBatchesPerRun: Int = 10

    /**
     * worker 身份。允许在一次运行内稳定复用（例如「主机名 + 线程名」）。
     *
     * 切换保护靠 `lease_generation` 而不是它的唯一性，详见
     * `agent/rca/runtime/RcaWorker.kt` 的说明——所以这里不强制随机。
     * 但**不能为空**：`RcaWorker` 的 `init` 会 `require` 非空，
     * 所以执行开关打开而本项为空时必须先由 [readinessErrors] 拦住。
     */
    var workerId: String = ""

    /**
     * worker 单次调度最多领取执行的任务数。
     *
     * 存在的意义与 `AiWorker` 的 `repeat(10)` 相同：XXL handler 是**一次调用跑一批**，
     * 不是常驻轮询进程。没有这个上限，一个 handler 调用可能在任务持续到达时永不返回。
     */
    var workerMaxTasksPerRun: Int = 10

    /**
     * worker 轮询间隔（毫秒）。
     *
     * **当前没有消费方**，与 [taskTimeoutMinutes] 属于同一类缺口，保留在配置里是为了让缺口可见。
     * 原因是执行侧的模型：XXL handler 每次调用只跑一批就返回，调度平台决定下一次何时来，
     * 中间不需要 worker 自己 sleep——自己 sleep 只会占住调度线程却不提高吞吐。
     * 这个键真正的消费方是一个常驻轮询进程，而本平台没有这种进程。
     */
    var workerDelayMs: Long = 1_000

    /** 租约时长（秒）。注意 `RcaWorker.DEFAULT_LEASE_SECONDS` 是 60，**以本配置为准**。 */
    var leaseSeconds: Long = 180

    /**
     * 任务超时（分钟）。**两个消费方，必须是同一个值**：
     *
     * 1. `RcaTaskLeaseAdapter.claim` 把它传给领取语句，由数据库在**首次领取**时写下
     *    `t_rca_agent_task.deadline_at = NOW(3) + N 分钟`（`IFNULL`，重试不重新计时）；
     * 2. `RcaRuntimeConfiguration` 用 [RcaBudgetPolicy.production] 造出图内的预算策略，
     *    同一个时长决定 `GapsAndBudget.deadlineAt`。
     *
     * 起算点是**领取时刻**，不是入库时刻：入库时刻起算会让「入库超过 30 分钟后才被领取」
     * 的任务一创建就已超期，从此永远不进图（`RcaWorker` 会在领取后立即收口）。
     *
     * 超期任务的处置是收口成终态而不是继续等：领取语句的候选条件里没有 `deadline_at`，
     * 所以超期任务**会**被领走，然后由 `RcaWorker.abandonOverdue` 结束掉。
     * 这样做是为了不让它停在「既非终态、又无执行者」的中间态上。
     */
    var taskTimeoutMinutes: Long = 30

    /**
     * 返回启用前的全部配置缺口，内容只包含稳定错误码。
     *
     * 只在对应开关打开时才校验：默认关闭时缺值不是错误，而是「没打算跑」。
     * 这样默认部署不会因为一条没配置的可选项而报就绪性错误。
     */
    fun readinessErrors(): List<String> = buildList {
        if (intakeEnabled) {
            if (intakeBatchSize !in 1..500) add("RCA_INTAKE_BATCH_SIZE_INVALID")
            if (intakeDataEnvironment !in DATA_ENVIRONMENTS) add("RCA_INTAKE_DATA_ENVIRONMENT_INVALID")
            if (intakeMaxBatchesPerRun !in 1..100) add("RCA_INTAKE_MAX_BATCHES_INVALID")
        }
        if (workerEnabled) {
            if (workerId.isBlank()) add("RCA_WORKER_ID_MISSING")
            if (leaseSeconds !in 1..3600) add("RCA_LEASE_SECONDS_INVALID")
            if (workerDelayMs !in 1..600_000) add("RCA_WORKER_DELAY_INVALID")
            if (workerMaxTasksPerRun !in 1..100) add("RCA_WORKER_MAX_TASKS_INVALID")
            // 上界 24 小时不是技术上限（列是 DATETIME(3)），是「一个 RCA 任务等这么久
            // 还不如让人来看一眼」；下界 1 分钟则是因为 0 会让每个任务一领到手就超期。
            if (taskTimeoutMinutes !in 1..1440) add("RCA_TASK_TIMEOUT_INVALID")
        }
    }

    /** 判断配置是否满足全部启用条件，不执行网络或数据库访问。 */
    fun ready(): Boolean = readinessErrors().isEmpty()

    /** 启动时记录就绪状态；worker-id 属于部署标识，不是凭据，但仍只记是否存在。 */
    @PostConstruct
    fun logReadiness() {
        logger.info(
            "RCA entry configuration: intakeEnabled={}, workerEnabled={}, dataEnvironment={}, ready={}, errors={}",
            intakeEnabled, workerEnabled, intakeDataEnvironment, ready(), readinessErrors()
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RcaProperties::class.java)

        /** 与 `chk_source_data_environment` 的三个取值保持一致。 */
        val DATA_ENVIRONMENTS: Set<String> = setOf("PRODUCTION", "TEST", "VERIFICATION")
    }
}
