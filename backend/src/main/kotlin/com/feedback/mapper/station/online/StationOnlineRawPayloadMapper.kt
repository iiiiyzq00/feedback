package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 原始密文载荷 Mapper；按来源单表幂等写入和有界读取。 */
@Mapper
interface StationOnlineRawPayloadMapper {
    /** 按唯一业务键幂等写入原始密文载荷；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsert(row: StationOnlineRawPayloadPo): Int
    /** 按受控条件查询原始密文载荷；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<StationOnlineRawPayloadPo>
}
