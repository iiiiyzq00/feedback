package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 渠道表 Mapper；提供固定锁序查询、缺失字段补齐和恢复状态推进。 */
@Mapper
interface StationLegacyChannelMapper {
    /** 按受控条件查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIdsForUpdate(@Param("sourceIds") sourceIds: List<Long>): List<StationLegacyChannelPo>
    /** 按受控条件查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findRecoveryPageForUpdate(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<StationLegacyChannelPo>
    /** 插入已校验的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: StationLegacyChannelPo): Int
    /** 只更新历史渠道当前为空的可补齐字段；参数化单表 SQL 不得覆盖既有来源事实。 */
    fun enrichMissing(row: StationLegacyChannelEnrichmentPo): Int
    /** 按当前快照条件推进 Station 渠道状态；联系方式、附件待处理状态和重试时间必须一致更新。 */
    fun applyState(row: StationLegacyChannelStatePo): Int
}
