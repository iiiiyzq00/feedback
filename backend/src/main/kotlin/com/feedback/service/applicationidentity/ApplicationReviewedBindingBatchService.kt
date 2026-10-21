package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*

/** reviewed binding 批量写 Service；一组已核验命令必须整体提交或回滚。 */
interface ApplicationReviewedBindingBatchService {
    /** 在一个短写事务中执行有界命令集合，并记录明确审核 actor。 */
    fun createAll(
        commands: List<ApplicationReviewedBindingCommand>,
        actor: Long
    ): List<ApplicationReviewedBindingResult>
}
