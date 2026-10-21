package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 来源表 Mapper；通过幂等键插入或定位权威来源记录。 */
@Mapper
interface StationOnlineSourceMapper {
    /** 插入并在唯一键竞争后定位权威反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertOrLocate(row: StationOnlineSourcePo): Int
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findById(@Param("sourceId") sourceId: Long): StationOnlineSourcePo?
    /** 按游标和显式上限分页查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findPageAfterId(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<StationOnlineSourcePo>
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByIds(@Param("sourceIds") sourceIds: List<Long>): List<StationOnlineSourcePo>
}
