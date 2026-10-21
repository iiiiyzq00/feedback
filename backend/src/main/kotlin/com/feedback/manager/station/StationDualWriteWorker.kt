package com.feedback.manager.station

import com.feedback.repository.remote.station.OldFbpGateway
import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.config.StationDualWriteProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.UUID

/** Station 双写 Worker；按 owner 租约处理单任务，外部调用不进入数据库事务。 */
@Service
class StationDualWriteWorker(
    private val tasks: StationDualWriteTaskService,
    private val oldFbp: OldFbpGateway,
    private val ingest: StationFeedbackIngestService,
    private val properties: StationDualWriteProperties
) {
    private val workerId = "station-dual-${UUID.randomUUID()}".take(100)

    /** 处理一个可领取任务；外部 HTTP、文件和高成本计算位于事务外，状态提交使用 owner 条件更新。 */
    fun processOne(): Boolean {
        if (!properties.enabled) {
            return false
        }
        val task = tasks.claimOne(workerId) ?: return false
        return try {
            if (!task.oldFbpSucceeded) {
                oldFbp.submit(task.envelope)
                tasks.markOldSucceeded(task.id, workerId)
            }
            if (!task.newPlatformSucceeded) {
                val envelope = task.envelope
                val result = ingest.accept(
                    envelope.request, envelope.version, envelope.idempotencyKey, envelope.principal
                )
                tasks.markCompleted(task.id, workerId, result.sourceId)
            }
            true
        } catch (error: Exception) {
            tasks.markFailure(task, workerId, error)
            throw error
        }
    }
}
