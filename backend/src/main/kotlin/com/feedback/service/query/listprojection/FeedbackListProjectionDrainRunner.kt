package com.feedback.service.query.listprojection

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 默认不存在的单动作启动期运维入口：把 dirty 队列按补偿轨排空一次。
 *
 * 与定时补偿（XXL handler `feedbackListProjectionRefreshHandler` → [FeedbackListProjectionRefreshWorker.drainScheduled]）
 * 调用的是同一个 worker，只是少了调度器；用途是没有 XXL 调度中心时让 dirty 队列仍然能被排空。
 * 它**只消费已经存在的 dirty 行**，不产生任何业务事实，也不改写投影以外的东西；
 * 不带 `preferredSourceIds`，因此领取范围是整表可领取行（租约过期、待重试、待处理），与定时补偿同口径。
 *
 * 与 BackfillRunner 一样：每次启动只做一件事，任何一步失败都以异常终止启动（不吞异常、不自动重试），
 * 运维方必须确认持久化状态后显式发起下一次。
 */
@Component
@ConditionalOnProperty(
    prefix = "app.phase-one.list-projection.drain",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class FeedbackListProjectionDrainRunner(
    private val worker: FeedbackListProjectionRefreshWorker,
    @Value("\${app.phase-one.list-projection.drain.max-batches:1}") private val maxBatches: Int,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        // 与 BackfillRunner 同一口径：启动期入参非法按统一项目异常暴露，不抛 Kotlin 守卫（ExceptionBoundaryArchitectureTest 门禁）。
        if (maxBatches !in 1..MAX_BATCHES_LIMIT) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_DRAIN_MAX_BATCHES_INVALID")
        var batches = 0
        var claimed = 0
        var acknowledged = 0
        var status = FeedbackListProjectionDrainStatus.NO_WORK
        while (batches < maxBatches) {
            val result = worker.drainOnce()
            status = result.status
            claimed += result.claimedCount
            acknowledged += result.acknowledgedCount
            batches++
            // 只有真的刷新了一整批才继续；NO_WORK / 无可写 generation / 已排重试都说明这一轮到头了。
            if (result.status != FeedbackListProjectionDrainStatus.REFRESHED) break
        }
        logger.info(
            "Feedback list projection drain finished: status={}, batches={}, claimedRows={}, acknowledgedRows={}",
            status,
            batches,
            claimed,
            acknowledged,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(FeedbackListProjectionDrainRunner::class.java)
        const val MAX_BATCHES_LIMIT = 500
    }
}
