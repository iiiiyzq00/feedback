package com.feedback.job

import com.feedback.service.query.FeedbackDistributionCompensationScheduler
import com.feedback.service.query.listprojection.FeedbackListProjectionRefreshWorker
import com.feedback.service.sync.DerivedIndexSyncScheduler
import com.feedback.service.sync.IndexMqTrack
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackDerivedIndexSyncHandler")
class FeedbackDerivedIndexSyncJob(target: DerivedIndexSyncScheduler) : DelegatingXxlJobHandler({ target.sync() })

/**
 * 索引同步的中继：把已认领的索引任务发到 MQ 主题（含 fencing 令牌）。
 *
 * 与上面的 `feedbackDerivedIndexSyncHandler` 是**两条互斥的轨**，开关在
 * `DerivedIndexSyncScheduler` / `IndexMqTrack` 各自的开关判断里，理由见
 * `DerivedIndexSyncScheduler` 的类文档——两条轨同时认领会互相 fencing。
 */
@Component @EnabledXxlJob @JobHandler("feedbackDerivedIndexRelayHandler")
class FeedbackDerivedIndexRelayJob(target: IndexMqTrack) : DelegatingXxlJobHandler({ target.relayPending() })

@Component @EnabledXxlJob @JobHandler("feedbackDistributionCompensationHandler")
class FeedbackDistributionCompensationJob(target: FeedbackDistributionCompensationScheduler) : DelegatingXxlJobHandler({ target.reconcile() })

@Component @EnabledXxlJob @JobHandler("feedbackListProjectionRefreshHandler")
class FeedbackListProjectionRefreshJob(target: FeedbackListProjectionRefreshWorker) : DelegatingXxlJobHandler({ target.drainScheduled() })
