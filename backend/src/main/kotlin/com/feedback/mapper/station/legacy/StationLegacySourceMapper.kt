package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 来源表 Mapper；按稳定记录号加锁定位并执行兼容字段补齐。 */
@Mapper
interface StationLegacySourceMapper {
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByRecordIdsForUpdate(@Param("recordIds") recordIds: List<String>): List<StationLegacySourcePo>
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByIds(@Param("sourceIds") sourceIds: List<Long>): List<StationLegacySourcePo>
    /** 插入已校验的反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: StationLegacySourcePo): Int
    /** 仅当 app_version 为空时补齐指定来源；使用参数化单表 UPDATE，返回受影响行数供 Repository 校验。 */
    fun fillMissingAppVersion(@Param("sourceId") sourceId: Long, @Param("appVersion") appVersion: String): Int
}
