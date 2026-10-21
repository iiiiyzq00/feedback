package com.feedback.mapper.scheduler

import com.feedback.model.po.scheduler.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface MaintenanceImportAttachmentTaskMapper {
    fun clearFinishedOrExpiredUrls(): Int
}

