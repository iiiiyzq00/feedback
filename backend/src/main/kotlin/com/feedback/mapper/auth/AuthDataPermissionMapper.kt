package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthDataPermissionPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 数据权限有界读取 Mapper；SQL 合并用户和角色主体，避免逐角色查询。 */
@Mapper
interface AuthDataPermissionMapper {
    /** 一次批量读取用户和角色的 READ 权限；limit+1 由 Repository 用作完整性哨兵。 */
    fun findReadPermissions(
        @Param("userId") userId: String,
        @Param("roles") roles: Set<String>,
        @Param("limit") limit: Int,
    ): List<AuthDataPermissionPo>
}
