package com.feedback.model.po.feedback.listprojection

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FeedbackListProjectionDirtyVersionPo(
    var sourceId: Long = 0,
    var mutationVersion: Long = 0
)

