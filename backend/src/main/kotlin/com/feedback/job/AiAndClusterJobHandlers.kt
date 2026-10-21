package com.feedback.job

import com.feedback.service.ai.AiEnqueueScheduler
import com.feedback.service.ai.AiWorkerScheduler
import com.feedback.service.ai.PromptAutoRollbackMonitor
import com.feedback.service.cluster.ClusterAiAssistedDecisionService
import com.feedback.service.cluster.ClusterStatisticsService
import com.feedback.service.cluster.ClusteringScheduler
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackClusterAiDecisionHandler")
class FeedbackClusterAiDecisionJob(target: ClusterAiAssistedDecisionService) : DelegatingXxlJobHandler({ target.processDue() })

@Component @EnabledXxlJob @JobHandler("feedbackClusteringHandler")
class FeedbackClusteringJob(target: ClusteringScheduler) : DelegatingXxlJobHandler({ target.clusterReadyRecords() })

@Component @EnabledXxlJob @JobHandler("feedbackClusterStatisticsHandler")
class FeedbackClusterStatisticsJob(target: ClusterStatisticsService) : DelegatingXxlJobHandler({ target.reconcile() })

@Component @EnabledXxlJob @JobHandler("feedbackAiEnqueueHandler")
class FeedbackAiEnqueueJob(target: AiEnqueueScheduler) : DelegatingXxlJobHandler({ target.enqueuePassRecords() })

@Component @EnabledXxlJob @JobHandler("feedbackAiWorkerHandler")
class FeedbackAiWorkerJob(target: AiWorkerScheduler) : DelegatingXxlJobHandler({ target.runPending() })

@Component @EnabledXxlJob @JobHandler("feedbackPromptAutoRollbackHandler")
class FeedbackPromptAutoRollbackJob(target: PromptAutoRollbackMonitor) : DelegatingXxlJobHandler({ target.ensureAiState() })
