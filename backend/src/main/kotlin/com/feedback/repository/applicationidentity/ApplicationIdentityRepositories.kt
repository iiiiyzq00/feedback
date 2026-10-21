package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*


/** SDK/站端等来源事实的有界批量读取边界。 */
interface ApplicationIdentitySourceRepository {
    /** 按稳定游标读取 SDK/站端来源；缺失、重复、乱序或越界均失败关闭。 */
    fun nextInputs(afterSourceId: Long, limit: Int): List<ApplicationIdentitySourceInput>

    /** 读取站端及三类文本证据渠道；未获批渠道不制造 alias。 */
    fun nextFourChannelInputs(afterSourceId: Long, limit: Int): List<ApplicationIdentitySourceInput>

    companion object {
        const val SHADOW_BATCH_SIZE = 100
    }
}

/** identity、版本化 alias、锁和 CAS 的唯一复杂数据访问契约。 */
interface ApplicationIdentityRepository {
    /** 批量读取活动 alias，并校验结果未越过请求键集合。 */
    fun activeAliases(keys: Collection<ApplicationAliasKey>): Map<ApplicationAliasKey, ActiveApplicationAlias>

    /** 批量创建 identity 并返回数据库生成 ID；调用方必须处于短写事务。 */
    fun createIdentities(rows: List<NewApplicationIdentity>): Map<String, ApplicationIdentitySnapshot>

    /** 批量创建自动 alias；唯一键负责拒绝并发双绑定。 */
    fun createAutoAliases(bindings: Map<ApplicationAliasKey, ApplicationIdentitySnapshot>)

    /** 按稳定 UUID 查询活动 identity。 */
    fun findByPublicId(publicId: String): ApplicationIdentitySnapshot?

    /** 按数据库 ID 查询 identity，仅供同一聚合内部完整性复核。 */
    fun findById(id: Long): ApplicationIdentitySnapshot?

    /** 有界检查规范展示名是否已被活动 identity 使用。 */
    fun activeByDisplayName(displayName: String): List<ApplicationIdentitySnapshot>

    /** 批量定位多个规范展示名，每个名称最多允许一个活动 identity。 */
    fun activeByDisplayNames(displayNames: Collection<String>): Map<String, List<ApplicationIdentitySnapshot>>

    /** 按稳定 UUID 游标查询活动 identity，limit 必须显式有界。 */
    fun listAfter(afterPublicId: String?, limit: Int): List<ApplicationIdentitySnapshot>

    /** 有界读取指定 identity 的 alias 历史版本。 */
    fun aliasesFor(identityId: Long): List<ApplicationIdentityAliasSnapshot>

    /** 锁前快照读 requestId 的历史结果，用于快速稳定重放。 */
    fun findManualResult(requestId: String): ApplicationIdentityAliasSnapshot?

    /** 锁后当前读 requestId 结果，避免 REPEATABLE READ 复用旧快照。 */
    fun lockManualResult(requestId: String): ApplicationIdentityAliasSnapshot?

    /** 首先锁定活动 alias，人工重绑不得先锁 identity。 */
    fun lockActiveAlias(aliasPublicId: String): ApplicationIdentityAliasSnapshot?

    /** 按传入顺序锁 identity；重绑固定传 source 后 target。 */
    fun lockIdentitiesInOrder(ids: List<Long>): Map<Long, ApplicationIdentitySnapshot>

    /** 对一个 identity 执行期望版本 CAS。 */
    fun incrementVersion(identityId: Long, expectedVersion: Long)

    /** 对有界 identity 集合执行双版本/批量 CAS，任一失败即冲突。 */
    fun incrementVersions(identities: Collection<ApplicationIdentitySnapshot>): Map<Long, ApplicationIdentitySnapshot>

    /** 以 alias 期望版本失效活动版本。 */
    fun supersedeAlias(row: ApplicationIdentityAliasSnapshot)

    /** 插入人工 alias 新版本；requestId 与活动键唯一约束提供并发栅栏。 */
    fun insertManualAlias(row: NewApplicationIdentityAlias)

    /** 聚合活动 alias 类型计数，结果转换为强类型键。 */
    fun aliasTypeCounts(): Map<ApplicationAliasType, Long>

    /** 生成稳定活动 alias 键哈希，编码规则属于持久化契约。 */
    fun activeKeyHex(key: ApplicationAliasKey): String

    companion object {
        const val MAX_BATCH_SIZE = 100
        const val MAX_ALIAS_BATCH_SIZE = 200
        const val MAX_ALIAS_HISTORY = 200
        const val MAX_PAGE_SIZE = 100

    }
}

/** 主问题影子事实的有界批量读写与诊断聚合契约。 */
interface PrimaryProblemFactRepository {
    /** 批量读取已审核/影子事实，不存在的 source ID 保持缺失。 */
    fun findBySourceIds(sourceIds: Collection<Long>): Map<Long, PrimaryProblemFact>

    /** 在短写事务内批量 UPSERT，重复来源或越界批次必须拒绝。 */
    fun saveAll(rows: List<PrimaryProblemFact>)

    /** 按强类型解析状态聚合诊断计数。 */
    fun resolutionCounts(): Map<ApplicationResolutionStatus, Long>
}
