package com.feedback.security

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.FeedbackChannel
import com.feedback.model.auth.DataAccessScope
import com.feedback.repository.feedback.access.FeedbackAccessFacts
import com.feedback.repository.feedback.access.FeedbackAccessRepository
import com.feedback.service.auth.DataPermissionService
import org.springframework.stereotype.Service

/** 一次反馈访问校验固定的数据范围与权威来源事实，供后续用例在同一调用链内复用。 */
data class FeedbackAccessContext(
    val facts: FeedbackAccessFacts,
    val scope: DataAccessScope
)

/**
 * 反馈按反馈 ID 访问的公共失败关闭边界。
 *
 * 任何业务事实读取前必须先确认已认证 SSO 主体具备反馈工作区权限；随后确认记录属于生产环境、
 * 五个反馈物理渠道、未删除且已生成安全正文，并应用当前主体的租户、渠道、模块和地域范围。不可见记录
 * 统一返回 `FEEDBACK_NOT_FOUND`，范围拒绝返回
 * `DATA_SCOPE_ACCESS_DENIED`；调用方不得绕过该边界读取附件、诊断、归簇或详情辅助事实。
 *
 * ## 跨租户为什么报「不存在」而不是「拒绝」
 *
 * 租户不匹配走 `FEEDBACK_NOT_FOUND`，与「记录不存在」完全同一个响应。若报
 * `DATA_SCOPE_ACCESS_DENIED`，调用方就能用一个 ID 探测出「这个 ID 在别的租户里存在」——
 * 那本身就是跨租户信息泄漏。渠道/模块/地域的拒绝报 `DATA_SCOPE_ACCESS_DENIED` 是因为
 * 它相对租户是**内层**概念：能走到那一步说明记录已经属于本租户，泄漏面小得多。
 */
@Service
class FeedbackAccessGuard(
    private val repository: FeedbackAccessRepository,
    private val permissions: DataPermissionService,
    private val principalProvider: CurrentWebPrincipalProvider
) {
    /** 在读取后续详情前统一校验工作区资格、生产安全事实和当前数据范围。 */
    fun require(sourceId: Long): FeedbackAccessContext {
        PrincipalAccess.requireWorkspace(principalProvider.require())
        val facts = repository.findBySourceId(sourceId)
            ?.takeIf {
                it.dataEnvironment == PRODUCTION_ENVIRONMENT &&
                    FeedbackChannel.fromStorage(it.sourceChannel) != null &&
                    !it.deleted &&
                    it.maskingStatus == SAFE_MASKING_STATUS
            }
            ?: throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        val scope = permissions.scope()
        // 租户先判：它是外层归属，判定只比字符串，也比渠道判定更早失败。
        if (!scope.permitsTenant(facts.tenant)) {
            throw ClientException(ErrorCode.NOT_FOUND, "FEEDBACK_NOT_FOUND")
        }
        if (!scope.permits(facts.sourceChannel, facts.functionModule, facts.provinceCode, facts.cityCode)) {
            throw ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "DATA_SCOPE_ACCESS_DENIED")
        }
        return FeedbackAccessContext(facts, scope)
    }

    private companion object {
        const val PRODUCTION_ENVIRONMENT = "PRODUCTION"
        const val SAFE_MASKING_STATUS = "PASS"
    }
}
