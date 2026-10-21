package com.feedback.service.auth

import com.feedback.model.auth.FeishuRoleMappingDecisionInput
import com.feedback.model.auth.FeishuRoleMappingInput

/** 飞书授权映射管理接口；写入、CAS 状态迁移和审计由实现的短事务统一提交。 */
interface FeishuRoleMappingService {
    /** 返回映射目录、审批统计和稳定规则优先级。 */
    fun list(): Map<String, Any>
    /** 创建草稿映射并在同一事务记录安全审计。 */
    fun create(input: FeishuRoleMappingInput): Map<String, Any>
    /** 按 expectedVersion 更新草稿，冲突时不写成功审计。 */
    fun update(id: Long, input: FeishuRoleMappingInput): Map<String, Any>
    /** 按状态机和版本 CAS 决策映射，并与审计原子提交。 */
    fun decide(id: Long, input: FeishuRoleMappingDecisionInput): Map<String, Any>
}
