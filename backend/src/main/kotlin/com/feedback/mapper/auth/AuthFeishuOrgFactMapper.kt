package com.feedback.mapper.auth

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 飞书用户组织事实 Mapper；按用户幂等保存组织快照，并独立记录最近同步失败。 */
@Mapper
interface AuthFeishuOrgFactMapper {
    /** 幂等保存用户最近组织事实与启用状态；JSON 必须在进入事务前完成序列化。 */
    fun upsert(
        @Param("userId") userId: Long,
        @Param("groupsJson") groupsJson: String,
        @Param("departmentsJson") departmentsJson: String,
        @Param("status") status: String,
    ): Int

    /** 只更新最近失败码，保留上一次成功组织事实供诊断。 */
    fun markFailure(
        @Param("userId") userId: Long,
        @Param("errorCode") errorCode: String,
    ): Int
}
