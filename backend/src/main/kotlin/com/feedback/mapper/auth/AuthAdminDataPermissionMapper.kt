package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthAdminDataScopePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 管理员配置的数据权限 Mapper；删除后重建范围必须由上层管理事务统一覆盖。 */
@Mapper
interface AuthAdminDataPermissionMapper {
    /** 按一页用户主体批量读取管理员显式范围，调用方须保证集合非空以避免非法 IN。 */
    fun findBySubjectIds(@Param("subjectIds") subjectIds: Set<String>): List<AuthAdminDataScopePo>

    /** 删除指定用户的管理员来源范围；必须与后续重建、authVersion 和审计同事务。 */
    fun deleteBySubjectId(subjectId: String): Int

    /** 插入一条已完成强类型校验的范围记录，返回影响行数供仓储失败关闭。 */
    fun insert(
        @Param("subjectId") subjectId: String,
        @Param("scopeType") scopeType: String,
        @Param("scopeValue") scopeValue: String,
        @Param("accessLevel") accessLevel: String,
        @Param("effect") effect: String,
    ): Int
}
