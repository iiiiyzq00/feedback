package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthPolicyScopePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 飞书策略生成的数据权限 Mapper；按用户替换策略来源的数据范围，不触碰管理员显式配置。 */
@Mapper
interface AuthPolicyDataPermissionMapper {
    /** 读取指定用户由飞书策略生成的范围，不包含管理员显式配置。 */
    fun findFeishuPolicyScopes(subjectId: String): List<AuthPolicyScopePo>

    /** 删除指定用户全部飞书策略范围；必须与后续重建处于同一用户写事务。 */
    fun deleteFeishuPolicyScopes(subjectId: String): Int

    /** 插入一条已计算的飞书策略范围，固定使用 READ/ALLOW 存储编码。 */
    fun insertFeishuPolicyScope(
        @Param("subjectId") subjectId: String,
        @Param("scopeType") scopeType: String,
        @Param("scopeValue") scopeValue: String,
    ): Int
}
