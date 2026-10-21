package com.feedback.mapper.scheduler

import com.feedback.model.po.scheduler.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface MaintenanceStationRawMapper {
    fun candidates(@Param("afterId") afterId: Long?, @Param("limit") limit: Int): List<MaintenanceStationCipherCandidatePo>
    fun clear(@Param("ids") ids: Collection<Long>): Int
}

