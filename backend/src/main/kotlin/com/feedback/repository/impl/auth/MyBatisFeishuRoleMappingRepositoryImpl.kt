package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthFeishuRoleMappingMapper
import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import com.feedback.repository.auth.FeishuRoleMappingRepository
import org.springframework.stereotype.Repository

/** MyBatis 飞书映射仓储实现；版本 CAS 影响行数原样返回，由 ServiceImpl 决定冲突错误。 */
@Repository
class MyBatisFeishuRoleMappingRepositoryImpl(
    private val mapper: AuthFeishuRoleMappingMapper,
) : FeishuRoleMappingRepository {
    /** 读取全部映射供管理端展示；列表边界由映射目录规模约束。 */
    override fun all() = mapper.findAll()

    /** 读取已审批映射供同步快照批量计算，避免逐用户查询规则。 */
    override fun approved() = mapper.findApproved()

    /** 插入映射并校验数据库生成主键；异常时触发上层事务回滚。 */
    override fun create(mapping: AuthFeishuRoleMappingPo): Long {
        if (mapper.insert(mapping) != 1) {
            failed("FEISHU_MAPPING_INSERT_FAILED")
        }
        return mapping.id ?: failed("FEISHU_MAPPING_ID_MISSING")
    }

    /** 按草稿状态和 expectedVersion 执行 CAS 更新，影响行数原样交给服务判定。 */
    override fun updateDraft(mapping: AuthFeishuRoleMappingPo) = mapper.updateDraft(mapping)

    /** 按当前状态与版本执行审批 CAS，保证并发决策不会互相覆盖。 */
    override fun decide(id: Long, status: String, reason: String, actor: Long, version: Long, current: String) =
        mapper.decide(id, status, reason, actor, version, current)

    /** 将插入不变量失败转换为稳定内部错误。 */
    private fun failed(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
}
