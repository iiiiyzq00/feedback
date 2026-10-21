package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 正文表 Mapper；按来源批量读取并插入已处理正文。 */
@Mapper
interface StationLegacyContentMapper {
    /** 按受控条件查询反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<StationLegacyContentPo>
    /** 插入已校验的反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: StationLegacyContentPo): Int
}
