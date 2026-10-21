package com.feedback.job.rca

import com.feedback.agent.rca.intake.RcaIntakeScheduler
import com.feedback.agent.rca.runtime.RcaDomainEventRelay
import com.feedback.agent.rca.runtime.RcaWorkerScheduler
import com.feedback.job.DelegatingXxlJobHandler
import com.feedback.job.EnabledXxlJob
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

/**
 * RCA 生产入口的 XXL 处理器。
 *
 * 本文件是**薄适配层**：只做「调度 → 调用」，不带任何业务判断，
 * 与 `job/AiAndClusterJobHandlers.kt`、`job/station/StationJobHandlers.kt` 同一个范式。
 * 事务、幂等、开关都在 `agent/rca/` 侧，本层只负责把调度平台的触发翻译成一次方法调用。
 *
 * 命名沿用仓库约定 `feedback*Handler`，与任务中心的配置项一致。
 */

/**
 * 接入：把 `t_feedback_source` 上的新来源会话化成问题对象并投递 RCA 任务。
 *
 * 在它之前，`EpisodeIntakeService.accept` 没有任何生产调用方——本处理器补的就是这一段。
 */
@Component @EnabledXxlJob @JobHandler("feedbackRcaIntakeHandler")
class FeedbackRcaIntakeJob(
    target: RcaIntakeScheduler
) : DelegatingXxlJobHandler({ target.runIntake() })

/**
 * 执行：领取待处理的 RCA 任务，跑完状态图并把终态与归因落库。
 *
 * 在它之前，`RcaWorker` 没有任何生产调用方——它只被离线回放与注入套件直接 `new` 出来过。
 *
 * **本处理器不碰租约**：`RcaWorker.runOnce()` 自己 claim。这里只驱动循环，
 * 开关与就绪检查都在 `RcaWorkerScheduler`。
 *
 * 执行侧开关关着时本类**仍然装配**，触发它得到的是「空转成功」而不是「handler 不存在」——
 * 这正是本文件开头那条约定（`app.rca.worker-enabled=false` 时
 * [RcaWorkerScheduler] 依赖的 `RcaWorker` 不装配，空转由它内部的 Provider 兜住）。
 */
@Component @EnabledXxlJob @JobHandler("feedbackRcaWorkerHandler")
class FeedbackRcaWorkerJob(
    target: RcaWorkerScheduler
) : DelegatingXxlJobHandler({ target.runPending() })

/**
 * 投递：把 `t_rca_domain_event` 里未投递的事件发到 MQ 主题。
 *
 * 与上面两个不同，本处理器**只在 `app.mq.enabled=true` 时才有事可做**：
 * 关闭时 [RcaDomainEventRelay.relayPending] 立刻返回。
 * 之所以仍然把 handler 与开关分开两层，是为了让「调度平台误触发」与
 * 「部署没有消息通道」互不牵连——开关关掉时这条调度仍然会被触发，
 * 只是什么也不做，而不是变成一个错误。
 */
@Component @EnabledXxlJob @JobHandler("feedbackRcaEventRelayHandler")
class FeedbackRcaEventRelayJob(
    target: RcaDomainEventRelay
) : DelegatingXxlJobHandler({ target.relayPending() })
