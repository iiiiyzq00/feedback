package com.feedback.model.po.scheduler

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class MaintenanceSdkCipherCandidatePo(
    var id: Long = 0,
    var feedbackSourceId: Long? = null,
)

