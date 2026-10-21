package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.model.applicationidentity.ApplicationReviewedBindingPreparation
import com.feedback.model.applicationidentity.ApplicationReviewedBindingPreflightReport

/** reviewed binding 批量预检 Service；仅验证当前快照，不产生任何写入。 */
interface ApplicationReviewedBindingPreflightService {
    /** 批量把不含目标 ID 的离线审核输入解析为有版本快照的正式命令，禁止逐项查询。 */
    fun prepare(inputs: List<ApplicationReviewedBindingPreparation>): List<ApplicationReviewedBindingCommand>

    /** 在一个短只读事务中检查最多 20 条互异决策。 */
    fun check(commands: List<ApplicationReviewedBindingCommand>): ApplicationReviewedBindingPreflightReport

    companion object {
        const val MAX_DECISIONS = 20
    }
}
