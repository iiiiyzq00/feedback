package com.feedback.job

import com.feedback.service.masking.AutomaticMaskingRecoveryScheduler
import com.feedback.service.masking.MaskingEvaluationEvidenceService
import com.feedback.service.masking.SdkSecureProcessingScheduler
import com.xxl.job.core.handler.annotation.JobHandler
import org.springframework.stereotype.Component

@Component @EnabledXxlJob @JobHandler("feedbackMaskingEvidenceExpiryHandler")
class FeedbackMaskingEvidenceExpiryJob(target: MaskingEvaluationEvidenceService) : DelegatingXxlJobHandler({ target.expireDatasets() })

@Component @EnabledXxlJob @JobHandler("feedbackMaskingRecoveryHandler")
class FeedbackMaskingRecoveryJob(target: AutomaticMaskingRecoveryScheduler) : DelegatingXxlJobHandler({ target.recover() })

@Component @EnabledXxlJob @JobHandler("feedbackSdkSecureProcessingHandler")
class FeedbackSdkSecureProcessingJob(target: SdkSecureProcessingScheduler) : DelegatingXxlJobHandler({ target.processPending() })
