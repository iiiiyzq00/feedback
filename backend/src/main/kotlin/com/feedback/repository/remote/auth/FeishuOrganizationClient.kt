package com.feedback.repository.remote.auth

import com.feedback.model.auth.FeishuOrganizationFacts

/** 飞书组织事实端口，返回授权策略所需的最小稳定字段集合。 */
interface FeishuOrganizationClient {
    /** 在事务外读取指定飞书用户的启用状态、部门和用户组事实。 */
    fun facts(openId: String): FeishuOrganizationFacts
}
