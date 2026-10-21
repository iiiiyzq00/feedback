package com.feedback.job.station

import com.feedback.config.StationDualWriteProperties
import com.feedback.manager.station.StationDualWriteWorker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Station 双写调度器；每轮最多处理五十个任务，遇到空队列立即停止。 */
@Component
class StationDualWriteWorkerScheduler(
    private val worker: StationDualWriteWorker,
    private val properties: StationDualWriteProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 按固定上限驱动待处理任务，遇到无任务即停止，防止调度入口形成无界循环。 */
    fun processPending() {
        if (!properties.enabled) {
            return
        }
        repeat(50) {
            runCatching { worker.processOne() }
                .onFailure { log.warn("Station dual write failed: errorType={}", it.javaClass.simpleName) }
                .getOrDefault(false)
                .let { processed ->
                    if (!processed) {
                        return
                    }
                }
        }
    }
}
