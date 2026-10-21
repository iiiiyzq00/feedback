package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthExternalIdentityPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 飞书外部身份表 Mapper。
 *
 * 按 openId 定位本地用户、批量读取身份并创建或刷新绑定资料；数据库唯一约束负责阻止
 * 同一飞书主体绑定多个本地账号，Mapper 本身不承担事务编排。
 */
@Mapper
interface AuthExternalIdentityMapper {
    /** 按飞书 openId 查找唯一身份绑定，依赖数据库唯一约束保证单值语义。 */
    fun findFeishuBySubject(providerSubject: String): AuthExternalIdentityPo?

    /** 读取全部飞书身份供显式全量同步；调用方负责同步任务级互斥和规模治理。 */
    fun findAllFeishu(): List<AuthExternalIdentityPo>

    /** 按分页用户集合批量装配飞书绑定状态，禁止调用方逐用户查询。 */
    fun findFeishuByUserIds(@Param("userIds") userIds: Set<Long>): List<AuthExternalIdentityPo>

    /** 创建身份绑定，唯一键冲突必须向上传播，不能覆盖既有账号归属。 */
    fun insert(identity: AuthExternalIdentityPo): Int

    /** 仅刷新既有飞书身份资料，不改变本地用户归属。 */
    fun updateFeishu(identity: AuthExternalIdentityPo): Int
}
