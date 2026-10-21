package com.feedback.model.po.scheduler

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class MaintenanceAttachmentCandidatePo(
    var id: Long = 0,
    var storagePath: String? = null,
)

