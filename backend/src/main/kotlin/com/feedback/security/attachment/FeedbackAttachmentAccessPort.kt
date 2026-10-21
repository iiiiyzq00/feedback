package com.feedback.security.attachment

import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.WebPrincipal

/** 当前请求的反馈范围、字段策略与认证主体访问边界。 */
interface FeedbackAttachmentAccessPort {
    /** 校验当前主体可访问指定反馈，失败时不得泄露附件事实。 */
    fun requireScope(sourceId: Long)

    /** 返回当前请求的字段可见性策略快照。 */
    fun fieldPolicy(): DataFieldPolicy

    /** 返回当前已认证 Web 主体，不存在时失败关闭。 */
    fun principal(): WebPrincipal
}
