package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppReceiptPo(var targetId: String = "", var readAt: LocalDateTime? = null)

