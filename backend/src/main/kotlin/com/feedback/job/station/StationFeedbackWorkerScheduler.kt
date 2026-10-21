package com.feedback.job.station

import com.feedback.manager.station.StationFeedbackWorker
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Station 在线 Worker 调度器；每轮最多并行处理二十个已领取任务。 */
@Component
class StationFeedbackWorkerScheduler(private val worker: StationFeedbackWorker) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val threadSequence = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(20) { task ->
        Thread(task, "station-evidence-${threadSequence.incrementAndGet()}").apply { isDaemon = true }
    }

    /** 按固定上限驱动待处理任务，遇到无任务即停止，防止调度入口形成无界循环。 */
    fun processPending() {
        val claimed = buildList {
            for (index in 0 until 20) {
                val sourceId = worker.claimOne() ?: break
                add(sourceId)
            }
        }
        CompletableFuture.allOf(*claimed.map { id ->
            CompletableFuture.runAsync({
                runCatching { worker.processOne(id) }
                    .onFailure { error ->
                        log.warn("Station feedback processing failed: sourceId={} errorType={}", id, error.javaClass.simpleName)
                    }
            }, executor)
        }.toTypedArray()).join()
    }

    /** 停止调度器持有的后台资源，等待中的租约任务由到期机制安全接管。 */
    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
