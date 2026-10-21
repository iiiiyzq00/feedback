package com.feedback.job

import com.feedback.audit.HttpAuditQueueWorker
import com.feedback.scheduler.MaintenanceSchedulers
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackLeaseRecoveryHandler")
class FeedbackLeaseRecoveryJob(target: MaintenanceSchedulers) : DelegatingXxlJobHandler({ target.leases() })

@Component @EnabledXxlJob @JobHandler("feedbackCipherCleanupHandler")
class FeedbackCipherCleanupJob(target: MaintenanceSchedulers) : DelegatingXxlJobHandler({ target.ciphers() })

@Component @EnabledXxlJob @JobHandler("feedbackHttpAuditQueueHandler")
class FeedbackHttpAuditQueueJob(target: HttpAuditQueueWorker) : DelegatingXxlJobHandler({ target.drain() })
