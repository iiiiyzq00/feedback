package com.feedback.service.auth

import com.feedback.model.auth.FeishuPolicyPreviewInput

/**
 * 飞书组织事实授权同步接口。
 *
 * 实现必须保证远程通讯录 I/O 在事务外，并以单用户短事务原子提交授权、authVersion 与组织事实。
 */
interface FeishuAuthorizationPolicyService {
    /** 返回同步开关、最近运行和所需飞书能力，不触发远程调用。 */
    fun status(): Map<String, Any?>
    /** 使用输入组织事实预演授权规则，不改变任何用户授权。 */
    fun preview(input: FeishuPolicyPreviewInput): Map<String, Any?>
    /** 执行显式全量同步；远程 I/O 在事务外，单用户授权在独立短事务提交。 */
    fun syncAll(): Map<String, Any>
}
