package com.feedback.service.query.listprojection

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 默认不存在的单动作启动期运维入口，不是 HTTP API，也不从登录会话取得操作者。
 *
 * 只有显式设置 `app.phase-one.list-projection.backfill.enabled=true` 才创建 Bean。每次应用启动只执行一个
 * CREATE、REFRESH、VERIFY、ACTIVATE 或 ROLLBACK；generation key 和 actor 必须显式有效，CREATE/REFRESH/
 * VERIFY 还要求 batch size 为 1..200。REFRESH 可显式设置 1..500 个批次，默认仍只执行一个批次；每批继续
 * 保持独立事务和既有校验。ACTIVATE/ROLLBACK 不消费批大小。actor 只在 Coordinator 中确认
 * `t_auth_user.is_active` 并写入创建/激活归因，不等同于授予新的业务角色或页面权限。
 *
 * Runner 不自动重试，也不把 CREATE 到 ACTIVATE 串成一次启动流程；REFRESH 循环必须有显式硬上限，避免
 * 隐式长事务、无界历史回填或
 * 未经逐步核验的切换。任一输入、状态、锁、epoch、dirty、checksum 或 CAS 校验失败都会使本次启动以异常终止；
 * 运维方必须在确认持久化状态后显式发起下一动作。
 */
@Component
@ConditionalOnProperty(
    prefix = "app.phase-one.list-projection.backfill",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class FeedbackListProjectionBackfillRunner(
    private val service: FeedbackListProjectionBackfillApplicationService,
    @Value("\${app.phase-one.list-projection.backfill.action:}") private val action: String,
    @Value("\${app.phase-one.list-projection.backfill.generation-key:}") private val generationKey: String,
    @Value("\${app.phase-one.list-projection.backfill.actor-id:0}") private val actorId: Long,
    @Value("\${app.phase-one.list-projection.backfill.batch-size:0}") private val batchSize: Int,
    @Value("\${app.phase-one.list-projection.backfill.max-batches:1}") private val maxBatches: Int
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val normalizedAction = action.trim().uppercase()
        val key = FeedbackListProjectionBackfillValidation.generationKey(generationKey)
        if (actorId <= 0) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_ACTOR_INVALID")
        when (normalizedAction) {
            "CREATE" -> service.create(FeedbackListProjectionBuildCommand(normalizedAction, key, actorId, batchSize))
            "REFRESH" -> refreshBatches(FeedbackListProjectionBuildCommand(normalizedAction, key, actorId, batchSize))
            "VERIFY" -> service.verify(FeedbackListProjectionBuildCommand(normalizedAction, key, actorId, batchSize))
            "ACTIVATE" -> service.activate(FeedbackListProjectionActivationCommand(normalizedAction, key, actorId))
            "ROLLBACK" -> service.rollback(FeedbackListProjectionRollbackCommand(normalizedAction, key, actorId))
            else -> invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_ACTION_INVALID")
        }
    }

    private fun refreshBatches(command: FeedbackListProjectionBuildCommand) {
        if (maxBatches !in 1..500) invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_MAX_BATCHES_INVALID")
        var result = service.refreshNextBatch(command)
        var completed = 1
        while (completed < maxBatches && result.cursorSourceId < result.fenceSourceId) {
            result = service.refreshNextBatch(command)
            completed++
        }
    }
}
