package com.feedback.repository

import com.feedback.model.ai.AiTask
import com.feedback.model.feedback.FeedbackAi
import com.feedback.mapper.ai.*
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.springframework.stereotype.Repository
import java.util.Optional

/** 保留原服务可见方法名的 MyBatis AI 任务 Repository。 */
@Repository
class AiTaskRepository(private val mapper: AiTaskMapper) {
    fun findById(id: Long): Optional<AiTask> = Optional.ofNullable(mapper.findById(id)?.toDomain())
    fun findByTaskKey(taskKey: String): AiTask? = mapper.findByTaskKey(taskKey)?.toDomain()

    fun save(task: AiTask): AiTask {
        if (task.id == 0L) {
            val row = AiTaskInsertPo(
                skillCode = requireNotNull(task.skillCode), promptVersion = requireNotNull(task.promptVersion),
                sourceId = requireNotNull(task.sourceId), taskKey = requireNotNull(task.taskKey),
                taskStatus = requireNotNull(task.taskStatus), inputSnapshot = requireNotNull(task.inputSnapshot),
                snapshotRetentionUntil = requireNotNull(task.snapshotRetentionUntil)
            )
            check(mapper.insert(row) == 1) { "AI_TASK_INSERT_FAILED" }
            return task.copy(id = requireNotNull(row.id))
        }
        val affected = mapper.update(task.toPo())
        check(affected in 0..1) { "AI_TASK_UPDATE_AFFECTED_ROWS_INVALID" }
        return task
    }

    fun saveAndFlush(task: AiTask): AiTask = save(task)

    // 位置参数转换：字段顺序必须与 AiTaskPo / AiTask 的声明顺序逐一对应，
    // 两个类的 leaseGeneration 都紧跟 leaseExpiresAt，改动其中一处必须同步另一处。
    private fun AiTaskPo.toDomain() = AiTask(
        id, recordType, skillCode, promptVersion, sourceId, taskKey, taskStatus, leaseHolder,
        leaseExpiresAt, leaseGeneration, nextRetryAt, retryCount, inputSnapshot, outputSnapshot,
        snapshotRetentionUntil, errorDetail, completedAt, createdAt, updatedAt
    )

    private fun AiTask.toPo() = AiTaskPo(
        id, recordType, skillCode, promptVersion, sourceId, taskKey, taskStatus, leaseHolder,
        leaseExpiresAt, leaseGeneration, nextRetryAt, retryCount, inputSnapshot, outputSnapshot,
        snapshotRetentionUntil, errorDetail, completedAt, createdAt, updatedAt
    )
}

/** 保留 AI 结果与派生索引原调用合同的单表 MyBatis Repository。 */
@Repository
class FeedbackAiRepository(private val mapper: FeedbackAiMapper) {
    fun findById(id: Long): Optional<FeedbackAi> = Optional.ofNullable(mapper.findById(id))
    fun save(row: FeedbackAi): FeedbackAi {
        val affected = mapper.upsert(row)
        check(affected in 0..2) { "FEEDBACK_AI_UPSERT_AFFECTED_ROWS_INVALID" }
        return row
    }
    fun findTop100BySearchIndexStatusOrderByUpdatedAtAsc(status: String): List<FeedbackAi> =
        mapper.findTopBySearchStatus(status, 100)
    fun findTop100ByVectorIndexStatusOrderByUpdatedAtAsc(status: String): List<FeedbackAi> =
        mapper.findTopByVectorStatus(status, 100)
}
