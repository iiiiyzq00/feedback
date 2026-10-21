package com.feedback.job.attachment

import com.feedback.job.DelegatingXxlJobHandler
import com.feedback.job.EnabledXxlJob
import com.feedback.manager.attachment.AttachmentLocalCleanupWorker
import com.feedback.manager.attachment.AttachmentObjectMigrationWorker
import com.feedback.service.attachment.AttachmentLogPreviewService
import com.feedback.manager.attachment.AttachmentMediaPreviewWorker
import com.feedback.security.attachment.AttachmentSecurityScanWorker
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

/** 媒体派生预览任务入口；只委派给有界 worker，不依赖持久化实现。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackAttachmentMediaPreviewHandler")
class FeedbackAttachmentMediaPreviewJob(
    target: AttachmentMediaPreviewWorker
) : DelegatingXxlJobHandler({ target.generateBatch() })

/** 安全扫描任务入口；扫描、审核和文件 I/O 由 worker 在事务外完成。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackAttachmentSecurityScanHandler")
class FeedbackAttachmentSecurityScanJob(
    target: AttachmentSecurityScanWorker
) : DelegatingXxlJobHandler({ target.scanPending() })

/** 本地副本清理任务入口；只委派精确单文件清理。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackAttachmentLocalCleanupHandler")
class FeedbackAttachmentLocalCleanupJob(
    target: AttachmentLocalCleanupWorker
) : DelegatingXxlJobHandler({ target.cleanupBatch() })

/** 对象迁移任务入口；只委派有界、可重试迁移批次。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackAttachmentObjectMigrationHandler")
class FeedbackAttachmentObjectMigrationJob(
    target: AttachmentObjectMigrationWorker
) : DelegatingXxlJobHandler({ target.migrateBatch() })

/** 日志预览回填任务入口；依赖 Service 接口而非实现或 Repository。 */
@Component
@EnabledXxlJob
@JobHandler("feedbackAttachmentLogPreviewHandler")
class FeedbackAttachmentLogPreviewJob(
    target: AttachmentLogPreviewService
) : DelegatingXxlJobHandler({ target.backfillBatch() })
