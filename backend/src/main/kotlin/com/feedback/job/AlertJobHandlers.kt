package com.feedback.job

import com.feedback.service.alert.AlertEngine
import com.feedback.service.alert.AlertLifecycleService
import com.feedback.service.alert.AlertProductionGovernanceService
import com.feedback.service.alert.AlertReviewGovernanceMetricService
import com.feedback.service.alert.AlertTrendBaselineService
import com.feedback.service.alert.NotificationDispatcher
import com.feedback.service.alert.NotificationEscalationService
import com.feedback.service.alert.NotificationMqTrack
import com.feedback.service.alert.NotificationWorkerScheduler
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackAlertEscalationScanHandler")
class FeedbackAlertEscalationScanJob(target: NotificationEscalationService) : DelegatingXxlJobHandler({ target.scanTimeouts() })

@Component @EnabledXxlJob @JobHandler("feedbackAlertGovernanceExpiryHandler")
class FeedbackAlertGovernanceExpiryJob(target: AlertProductionGovernanceService) : DelegatingXxlJobHandler({ target.expireGovernance() })

@Component @EnabledXxlJob @JobHandler("feedbackAlertTrendBaselineHandler")
class FeedbackAlertTrendBaselineJob(target: AlertTrendBaselineService) : DelegatingXxlJobHandler({ target.refresh() })

@Component @EnabledXxlJob @JobHandler("feedbackAlertInputQualityHandler")
class FeedbackAlertInputQualityJob(target: AlertReviewGovernanceMetricService) : DelegatingXxlJobHandler({ target.snapshotContractInputs() })

@Component @EnabledXxlJob @JobHandler("feedbackAlertEvaluationHandler")
class FeedbackAlertEvaluationJob(target: AlertEngine) : DelegatingXxlJobHandler({ target.scheduledEvaluate() })

@Component @EnabledXxlJob @JobHandler("feedbackAlertLifecycleHandler")
class FeedbackAlertLifecycleJob(target: AlertLifecycleService) : DelegatingXxlJobHandler({ target.escalate() })

@Component @EnabledXxlJob @JobHandler("feedbackP3DailyDigestHandler")
class FeedbackP3DailyDigestJob(target: NotificationDispatcher) : DelegatingXxlJobHandler({ target.deliverP3DailyDigest() })

@Component @EnabledXxlJob @JobHandler("feedbackNotificationWorkerHandler")
class FeedbackNotificationWorkerJob(target: NotificationWorkerScheduler) : DelegatingXxlJobHandler({ target.scheduledDeliver() })

/**
 * 通知投递的中继：把待投递的记录号发到 MQ 主题（正文留在库里）。
 *
 * 与上面的 `feedbackNotificationWorkerHandler` 是**两条互斥的轨**：
 * `app.mq.enabled=false` 时那个处理器干活、这个空转；打开时反过来
 * （见 `NotificationWorkerScheduler` 的说明）。两个 handler 都保留，
 * 是为了让切换开关不需要改调度平台的配置。
 */
@Component @EnabledXxlJob @JobHandler("feedbackNotificationRelayHandler")
class FeedbackNotificationRelayJob(target: NotificationMqTrack) : DelegatingXxlJobHandler({ target.relayPending() })
