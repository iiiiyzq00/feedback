package com.feedback.service.impl.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.manager.applicationidentity.ApplicationIdentityReviewedBindingWorker
import com.feedback.service.applicationidentity.ApplicationReviewedBindingBatchService
import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.service.applicationidentity.ApplicationReviewedBindingPreflightService
import com.feedback.model.applicationidentity.ApplicationReviewedBindingResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** reviewed binding 批量写实现；内部 Worker 加入同一外层事务，禁止部分治理提交。 */
@Service
class ApplicationReviewedBindingBatchServiceImpl(
    private val worker: ApplicationIdentityReviewedBindingWorker
) : ApplicationReviewedBindingBatchService {
    /** 有界命令集合在一个短事务中依序写入，每条审计携带同一明确 actor。 */
    @Transactional
    override fun createAll(
        commands: List<ApplicationReviewedBindingCommand>,
        actor: Long
    ): List<ApplicationReviewedBindingResult> {
        if (commands.size !in 1..ApplicationReviewedBindingPreflightService.MAX_DECISIONS) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_REVIEW_BATCH_SCOPE_INVALID")
        }
        if (actor <= 0) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_REVIEW_APPLY_ACTOR_INVALID")
        }
        return commands.map { command -> worker.create(command, actor) }
    }
}
