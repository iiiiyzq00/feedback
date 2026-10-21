package com.feedback.job

import com.feedback.service.imports.ImportBatchWorkerScheduler
import com.feedback.service.imports.ImportUploadStagingWorker
import com.feedback.service.imports.UnifiedDataQualityService
import com.feedback.service.jira.Jira400SyncScheduler
import com.feedback.service.jira.Jira400SyncWorkerScheduler
import com.feedback.service.jira.JiraAttachmentPullWorkerScheduler
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackDataQualityRefreshHandler")
class FeedbackDataQualityRefreshJob(target: UnifiedDataQualityService) : DelegatingXxlJobHandler({ target.scheduledRefresh() })

@Component @EnabledXxlJob @JobHandler("feedbackImportBatchWorkerHandler")
class FeedbackImportBatchWorkerJob(target: ImportBatchWorkerScheduler) : DelegatingXxlJobHandler({ target.runPending() })

@Component @EnabledXxlJob @JobHandler("feedbackImportStagingWorkerHandler")
class FeedbackImportStagingWorkerJob(target: ImportUploadStagingWorker) : DelegatingXxlJobHandler({ target.runPending() })

@Component @EnabledXxlJob @JobHandler("feedbackJiraScheduleHandler")
class FeedbackJiraScheduleJob(target: Jira400SyncScheduler) : DelegatingXxlJobHandler({ target.schedule() })

@Component @EnabledXxlJob @JobHandler("feedbackJiraWorkerHandler")
class FeedbackJiraWorkerJob(target: Jira400SyncWorkerScheduler) : DelegatingXxlJobHandler({ target.runQueued() })

@Component @EnabledXxlJob @JobHandler("feedbackJiraAttachmentWorkerHandler")
class FeedbackJiraAttachmentWorkerJob(target: JiraAttachmentPullWorkerScheduler) : DelegatingXxlJobHandler({ target.runPending() })
