package com.feedback.repository.station.legacy

import com.feedback.model.po.station.legacy.*

/** Station Legacy 多表事实的唯一仓储契约；实现仅负责 MyBatis 映射与边界校验。 */
interface StationLegacyRepository {
    companion object {
        /** 文件与数据库批次共享的硬上限，防止无界锁集合。 */
        const val MAX_BATCH_SIZE = 200
    }

    /** 按受控条件查询 Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun sourcesByRecordIdsForUpdate(recordIds: List<String>): List<StationLegacySourcePo>
    /** 按受控条件查询 Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun sourcesByIds(sourceIds: List<Long>): Map<Long, StationLegacySourcePo>
    /** 插入已校验的Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertSource(row: StationLegacySourcePo)
    /** 仅在既有值为空时补齐来源应用版本；不得覆盖已保存版本，调用方据影响行数决定是否标记投影。 */
    fun fillMissingAppVersion(sourceId: Long, appVersion: String): Boolean
    /** 按受控条件查询 Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun contents(sourceIds: List<Long>): Map<Long, StationLegacyContentPo>
    /** 插入已校验的Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertContent(row: StationLegacyContentPo)
    /** 按来源 ID 的稳定顺序锁定历史渠道；集合必须有界，调用方需在行级导入短事务中持有锁。 */
    fun channelsForUpdate(sourceIds: List<Long>): Map<Long, StationLegacyChannelPo>
    /** 按游标锁定一页可恢复历史渠道；页大小受仓储上限约束，活动租约由应用层再次判断。 */
    fun recoveryPageForUpdate(afterSourceId: Long): List<StationLegacyChannelPo>
    /** 插入已校验的Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertChannel(row: StationLegacyChannelPo)
    /** 只补齐历史渠道的空字段；已有权威值不得覆盖，返回值表示是否发生实际更新。 */
    fun enrichChannel(row: StationLegacyChannelEnrichmentPo): Boolean
    /** 原子推进历史渠道恢复状态、重试时间及安全错误信息；必须加入调用方当前短事务。 */
    fun applyChannelState(row: StationLegacyChannelStatePo)
    /** 返回有原始密文载荷的来源 ID 集合；输入 ID 去重且有界，结果不包含载荷正文。 */
    fun rawSourceIds(sourceIds: List<Long>): Set<Long>
    /** 按受控条件查询 Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun identities(sourceIds: List<Long>): Map<Long, Set<String>>
    /** 插入已校验的Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertIdentity(row: StationLegacyIdentityPo): Boolean
    /** 按受控条件查询 Station 历史记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun sourcesWithEvidence(sourceIds: List<Long>): Set<Long>
}
