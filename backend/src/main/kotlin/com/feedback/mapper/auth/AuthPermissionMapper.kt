package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthPermissionPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 原子权限目录 Mapper；供角色管理读取全部权限或校验指定权限编码。 */
@Mapper
interface AuthPermissionMapper {
    /** 返回权限目录全量用于管理矩阵；目录属于平台控制的小规模集合。 */
    fun findAll(): List<AuthPermissionPo>

    /** 批量校验请求权限码是否均已登记，调用方保证 codes 非空。 */
    fun findByCodes(@Param("codes") codes: Set<String>): List<AuthPermissionPo>
}
