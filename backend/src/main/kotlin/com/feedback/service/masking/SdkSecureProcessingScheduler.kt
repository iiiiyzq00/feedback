package com.feedback.service.masking

import com.feedback.repository.sdk.SdkProcessingEnvelopeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** SDK 安全处理 Worker；通过 Spring 代理调用处理服务，确保每个信封拥有独立短事务。 */
@Component
class SdkSecureProcessingWorker(
    private val ingestRepository: SdkProcessingEnvelopeRepository,
    private val processingService: SdkSecureProcessingService
) {
    private val log = LoggerFactory.getLogger(javaClass)

/** 触发一次有界 SDK 安全处理轮次；不在调度入口创建长事务。 */
    fun processPending() {
        ingestRepository.findPending(50).forEach { ingest ->
            runCatching { processingService.processOne(ingest.id) }
                .onFailure { log.warn("Secure processing failed for ingest_id={} code={}", ingest.id, it.javaClass.simpleName) }
        }
    }
}

/** SDK 安全处理调度入口；只触发有界 Worker，不持有数据库事务。 */
@Component
class SdkSecureProcessingScheduler(private val worker: SdkSecureProcessingWorker) {
/** 触发一次有界 SDK 安全处理轮次；不在调度入口创建长事务。 */
    fun processPending() = worker.processPending()
}
