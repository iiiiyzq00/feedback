package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 正文表 Mapper；保存脱敏结果并支持受控脱敏恢复分页。 */
@Mapper
interface StationOnlineContentMapper {
    /** 插入已校验的反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: StationOnlineContentPo): Int
    /** 按受控条件查询反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<StationOnlineContentPo>
    /** 按前置状态条件更新反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markMaskingPass(row: StationOnlineContentPo): Int
    /** 按受控条件查询反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findMaskingReviewSourceIdsAfterId(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<Long>
}
