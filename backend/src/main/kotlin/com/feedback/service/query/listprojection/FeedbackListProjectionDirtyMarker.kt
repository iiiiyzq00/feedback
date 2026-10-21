package com.feedback.service.query.listprojection

import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionControlRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class FeedbackListProjectionDirtyCommand(
    val sourceIds: List<Long>,
    val reason: FeedbackListProjectionDirtyReason
)

data class FeedbackListProjectionDirtyCommittedEvent(val sourceIds: List<Long>)

/**
 * 权威业务事务标记列表投影变更的唯一入口。
 *
 * 标记与权威事实必须位于同一事务；当前已批准的反馈写面通过此入口递增 mutation epoch 并持久化 dirty。
 * 未来任何新增或绕开的权威写面都必须先接入本入口，才能纳入 generation 完整性声明。事件仅用于提交后的
 * 即时尝试，可能丢失或执行失败；可靠恢复始终以数据库 dirty 行为准。
 */
@Component
class FeedbackListProjectionDirtyMarker(
    private val repository: FeedbackListProjectionDirtyRepository,
    private val controlRepository: FeedbackListProjectionControlRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun mark(command: FeedbackListProjectionDirtyCommand) {
        val epoch = controlRepository.lockControl()
        controlRepository.incrementLockedEpoch(epoch)
        repository.mark(command.sourceIds, command.reason)
        eventPublisher.publishEvent(FeedbackListProjectionDirtyCommittedEvent(command.sourceIds.distinct().sorted()))
    }
}
