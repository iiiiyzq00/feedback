package com.feedback.job.station

import com.feedback.job.DelegatingXxlJobHandler
import com.feedback.job.EnabledXxlJob
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

/** Station 双写补偿任务入口；只委托有界调度器，调度线程不持有数据库事务。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackStationDualWriteWorkerHandler")
class FeedbackStationDualWriteWorkerJob(
    target: StationDualWriteWorkerScheduler,
) : DelegatingXxlJobHandler({ target.processPending() })

/** Station 反馈物化任务入口；只委托有界调度器，调度线程不持有数据库事务。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackStationWorkerHandler")
class FeedbackStationWorkerJob(
    target: StationFeedbackWorkerScheduler,
) : DelegatingXxlJobHandler({ target.processPending() })
