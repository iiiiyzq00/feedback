package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkChannelPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 渠道物化表 Mapper；提供有界分页、幂等写入和受控诊断条件更新。 */
@Mapper
interface SdkChannelMutationMapper {
    /** 按游标和显式上限分页查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findPageAfterId(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<SdkChannelPo>
    /** 按唯一业务键幂等写入渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsert(row: SdkChannelPo): Int
    /** 以条件更新推进渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun updateControlledDiagnostics(row: SdkChannelPo): Int
    /** 按唯一业务键幂等写入渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsertControlledDiagnostics(@Param("rows") rows: List<SdkChannelPo>): Int
}
