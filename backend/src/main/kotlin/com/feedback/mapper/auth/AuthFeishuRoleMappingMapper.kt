package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthFeishuRoleMappingPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 飞书角色映射规则 Mapper；草稿更新和决策通过期望版本执行条件写入。 */
@Mapper
interface AuthFeishuRoleMappingMapper {
    /** 按稳定优先级返回管理视图所需的全部映射。 */
    fun findAll(): List<AuthFeishuRoleMappingPo>

    /** 仅返回已审批规则，作为一次同步运行的只读规则快照。 */
    fun findApproved(): List<AuthFeishuRoleMappingPo>

    /** 插入已规范化草稿并回填主键，影响行数由仓储校验。 */
    fun insert(mapping: AuthFeishuRoleMappingPo): Int

    /** 仅当仍为草稿且版本匹配时更新，0 表示状态或版本冲突。 */
    fun updateDraft(mapping: AuthFeishuRoleMappingPo): Int

    /** 通过当前状态和期望版本双条件执行审批迁移，防止并发决策覆盖。 */
    fun decide(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("reason") reason: String,
        @Param("actor") actor: Long,
        @Param("version") version: Long,
        @Param("current") current: String,
    ): Int
}
