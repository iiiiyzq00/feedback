package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.ApplicationAliasRebindCommand
import com.feedback.model.applicationidentity.ApplicationAliasRebindResult
import com.feedback.model.applicationidentity.ApplicationIdentityAliasItem
import com.feedback.model.applicationidentity.ApplicationIdentityDiagnostics
import com.feedback.model.applicationidentity.ApplicationIdentityPage
import com.feedback.model.applicationidentity.ApplicationReviewedBindingCommand
import com.feedback.model.applicationidentity.ApplicationReviewedBindingResult

/** 管理员应用身份查询与人工绑定的唯一业务 Service 契约。 */
interface ApplicationIdentityAdminService {
    /** 返回影子解析与活动 alias 聚合诊断；调用方必须已认证。 */
    fun diagnostics(): ApplicationIdentityDiagnostics

    /** 按稳定 UUID 游标返回有界 identity 页面。 */
    fun list(afterApplicationId: String?, limit: Int): ApplicationIdentityPage

    /** 返回单个 identity 的有界 alias 历史，包含已失效版本。 */
    fun aliases(applicationId: String): List<ApplicationIdentityAliasItem>

    /** 执行 requestId 幂等的人工重绑；同号异命令冲突。 */
    fun rebind(command: ApplicationAliasRebindCommand): ApplicationAliasRebindResult

    /** 将已审核 Station 候选创建为新 identity 或绑定到既有 identity。 */
    fun createReviewedBinding(command: ApplicationReviewedBindingCommand): ApplicationReviewedBindingResult
}
