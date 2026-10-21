package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 身份表 Mapper；查询有界来源集合并以唯一约束忽略重复身份。 */
@Mapper
interface StationLegacyIdentityMapper {
    /** 按受控条件查询来源身份；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<StationLegacyIdentityPo>
    /** 插入已校验的来源身份；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertIgnore(row: StationLegacyIdentityPo): Int
}
