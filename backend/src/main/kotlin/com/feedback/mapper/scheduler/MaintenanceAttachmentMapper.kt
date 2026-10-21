package com.feedback.mapper.scheduler

import com.feedback.model.po.scheduler.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface MaintenanceAttachmentMapper {
    fun candidates(@Param("limit") limit: Int): List<MaintenanceAttachmentCandidatePo>
    fun markDeleted(@Param("ids") ids: Collection<Long>): Int
}

