package com.feedback.job

import com.feedback.scheduler.ReportSchedulers
import com.feedback.scheduler.VersionReleaseReportScheduler
import com.feedback.service.report.ReportGenerationScheduler
import com.feedback.service.report.ReportTaskLifecycle
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackDailyReportHandler")
class FeedbackDailyReportJob(target: ReportSchedulers) : DelegatingXxlJobHandler({ target.daily() })

@Component @EnabledXxlJob @JobHandler("feedbackWeeklyReportHandler")
class FeedbackWeeklyReportJob(target: ReportSchedulers) : DelegatingXxlJobHandler({ target.weekly() })

@Component @EnabledXxlJob @JobHandler("feedbackVersionReleaseReportHandler")
class FeedbackVersionReleaseReportJob(target: VersionReleaseReportScheduler) : DelegatingXxlJobHandler({ target.due() })

@Component @EnabledXxlJob @JobHandler("feedbackReportWorkerHandler")
class FeedbackReportWorkerJob(target: ReportGenerationScheduler) : DelegatingXxlJobHandler({ target.retryPending() })

@Component @EnabledXxlJob @JobHandler("feedbackReportLeaseRecoveryHandler")
class FeedbackReportLeaseRecoveryJob(target: ReportTaskLifecycle) : DelegatingXxlJobHandler({ target.recoverExpired() })
