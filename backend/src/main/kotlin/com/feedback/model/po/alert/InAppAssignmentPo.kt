package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppAssignmentPo(var feedbackId: Long = 0, var ownerId: Long = 0)

