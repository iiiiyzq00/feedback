package com.feedback.job.sdk

import com.feedback.job.DelegatingXxlJobHandler
import com.feedback.job.EnabledXxlJob
import com.feedback.manager.sdk.SdkAttachmentOrphanCleaner
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

/** FeedbackSdkTelemetryRetentionJob 任务入口；只委托领域 Worker/Service，调度线程不持有数据库事务。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackSdkTelemetryRetentionHandler")
class FeedbackSdkTelemetryRetentionJob(target: SdkRequestTelemetryRecorder) : DelegatingXxlJobHandler({ target.purgeExpired() })

/** FeedbackSdkAttachmentOrphanCleanupJob 任务入口；只委托领域 Worker/Service，调度线程不持有数据库事务。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackSdkAttachmentOrphanCleanupHandler")
class FeedbackSdkAttachmentOrphanCleanupJob(target: SdkAttachmentOrphanCleaner) : DelegatingXxlJobHandler({ target.clean() })
