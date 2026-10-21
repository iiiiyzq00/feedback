package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionActorMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionControlMapper
import org.springframework.stereotype.Repository

/** 全局切换锁、单调 epoch 与受控运维操作者验证的单表边界。 */
@Repository
class FeedbackListProjectionControlRepository(
    private val controlMapper: FeedbackListProjectionControlMapper,
    private val actorMapper: FeedbackListProjectionActorMapper
) {
    fun lockControl(): Long = controlMapper.findForUpdate()?.mutationEpoch
        ?: projectionBuildFailed("FEEDBACK_LIST_PROJECTION_CONTROL_MISSING")

    fun incrementLockedEpoch(currentEpoch: Long): Long {
        if (currentEpoch !in 0 until Long.MAX_VALUE) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_CONTROL_EPOCH_INVALID")
        if (controlMapper.incrementEpoch(currentEpoch) != 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_CONTROL_EPOCH_CAS_FAILED")
        return currentEpoch + 1
    }

    fun requireActiveActor(actorId: Long) {
        if (actorId <= 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_ACTOR_INVALID")
        if (actorMapper.isActive(actorId) != true) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_ACTOR_INACTIVE")
    }
}
