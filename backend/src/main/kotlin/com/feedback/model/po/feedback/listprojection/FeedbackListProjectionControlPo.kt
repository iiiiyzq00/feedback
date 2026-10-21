package com.feedback.model.po.feedback.listprojection

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FeedbackListProjectionControlPo(
    var controlId: Int = 1,
    var mutationEpoch: Long = 0,
    var updatedAt: LocalDateTime? = null
)

