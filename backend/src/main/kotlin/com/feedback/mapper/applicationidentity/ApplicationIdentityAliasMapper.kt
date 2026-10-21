package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_application_identity_alias 单表 Mapper；锁与 CAS 语义由方法注释冻结。 */
@Mapper
interface ApplicationIdentityAliasMapper {
    /** 按有界活动键哈希批量读取活动 alias。 */
    fun findActiveByKeyHexes(@Param("keyHexes") keyHexes: List<String>): List<ApplicationIdentityAliasPo>
    /** 按 alias UUID 锁定活动版本，是人工重绑第一把锁。 */
    fun lockActiveByPublicId(@Param("aliasPublicId") aliasPublicId: String): ApplicationIdentityAliasPo?
    /** requestId 普通快照读，仅用于锁前快速重放。 */
    fun findByRequestId(@Param("requestId") requestId: String): ApplicationIdentityAliasPo?
    /** requestId 当前锁读，用于写事务锁后幂等复核。 */
    fun lockByRequestId(@Param("requestId") requestId: String): ApplicationIdentityAliasPo?
    /** 有界读取 identity 的 alias 全部历史版本。 */
    fun findByIdentityId(@Param("identityId") identityId: Long, @Param("limit") limit: Int): List<ApplicationIdentityAliasPo>
    /** 插入单个人工 alias 版本，requestId 与活动键唯一约束负责并发冲突。 */
    fun insert(@Param("row") row: ApplicationIdentityAliasPo): Int
    /** 批量插入自动同源 alias，调用方强制集合上限。 */
    fun insertAll(@Param("rows") rows: List<ApplicationIdentityAliasPo>): Int
    /** 以 id、期望 alias 版本和 ACTIVE 状态 CAS 失效旧版本。 */
    fun supersede(@Param("id") id: Long, @Param("expectedVersion") expectedVersion: Long): Int
    /** 按 alias 类型聚合活动版本计数。 */
    fun countActiveByType(): List<ApplicationAliasTypeCountPo>
}
