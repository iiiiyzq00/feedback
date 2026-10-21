package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 原始载荷 Mapper；仅查询密文载荷存在性，不传播原始正文。 */
@Mapper
interface StationLegacyRawPayloadMapper {
    /** 按受控条件查询原始密文载荷；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findExistingSourceIds(@Param("sourceIds") sourceIds: List<Long>): List<Long>
}
