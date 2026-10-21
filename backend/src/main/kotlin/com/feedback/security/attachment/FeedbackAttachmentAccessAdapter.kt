package com.feedback.security.attachment

import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.security.FeedbackAccessGuard
import com.feedback.security.WebPrincipal
import com.feedback.service.auth.DataPermissionService
import org.springframework.stereotype.Component

/**
 * 将附件用例的窄访问端口接到平台统一的反馈范围、字段策略和当前主体边界。
 *
 * 本适配器不缓存授权结果；签发和消费一次性 grant 时都会重新执行权威范围校验，
 * 从而保留权限撤销后的失败关闭语义。
 */
@Component
class FeedbackAttachmentAccessAdapter(
    private val feedbackAccess: FeedbackAccessGuard,
    private val permissions: DataPermissionService,
    private val principals: CurrentWebPrincipalProvider,
) : FeedbackAttachmentAccessPort {
    /** 校验调用方对指定反馈源的读取范围；越权时由权威守卫抛失败关闭异常。 */
    override fun requireScope(sourceId: Long) {
        feedbackAccess.require(sourceId)
    }

    /** 返回当前调用方的数据字段策略，决定哪些字段按脱敏档位返回。 */
    override fun fieldPolicy(): DataFieldPolicy = permissions.fieldPolicy()

    /** 返回当前 Web 调用主体；未认证时由 provider 抛失败关闭异常，绝不降级为匿名。 */
    override fun principal(): WebPrincipal = principals.require()
}
