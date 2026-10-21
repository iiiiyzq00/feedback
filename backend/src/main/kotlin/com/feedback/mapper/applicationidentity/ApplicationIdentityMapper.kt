package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_application_identity 单表 Mapper；只承载 identity 查询、行锁与版本 CAS。 */
@Mapper
interface ApplicationIdentityMapper {
    /** 批量插入有界 identity 集合。 */
    fun insertAll(@Param("rows") rows: List<ApplicationIdentityPo>): Int
    /** 按数据库 ID 批量读取 alias 所属 identity。 */
    fun findByIds(@Param("ids") ids: List<Long>): List<ApplicationIdentityPo>
    /** 按稳定 UUID 批量回读新建 identity。 */
    fun findByPublicIds(@Param("publicIds") publicIds: List<String>): List<ApplicationIdentityPo>
    /** 按稳定 UUID 读取一个 identity。 */
    fun findByPublicId(@Param("publicId") publicId: String): ApplicationIdentityPo?
    /** 按数据库 ID 读取一个 identity。 */
    fun findById(@Param("id") id: Long): ApplicationIdentityPo?
    /** 最多读取两条活动同名 identity 以识别冲突。 */
    fun findActiveByDisplayName(@Param("displayName") displayName: String, @Param("limit") limit: Int): List<ApplicationIdentityPo>
    /** 批量读取审核目标展示名，整体 limit 防止异常重复数据导致无界结果。 */
    fun findActiveByDisplayNames(
        @Param("displayNames") displayNames: List<String>,
        @Param("limit") limit: Int
    ): List<ApplicationIdentityPo>
    /** 按稳定 UUID 游标读取有界活动 identity 页面。 */
    fun findAfterPublicId(@Param("afterPublicId") afterPublicId: String?, @Param("limit") limit: Int): List<ApplicationIdentityPo>
    /** 人工重绑按角色固定顺序逐行加锁，避免 Mapper 自行重排 source/target。 */
    fun lockById(@Param("id") id: Long): ApplicationIdentityPo?
    /** 对单个 identity 执行 mappingVersion CAS。 */
    fun compareAndIncrementVersion(@Param("id") id: Long, @Param("expectedVersion") expectedVersion: Long): Int
    /** 用一条条件 UPDATE 原子推进有界 identity 版本集合。 */
    fun compareAndIncrementVersions(@Param("rows") rows: List<ApplicationIdentityVersionCasPo>): Int
}
