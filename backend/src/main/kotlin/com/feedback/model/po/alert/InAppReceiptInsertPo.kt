package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppReceiptInsertPo(var userId: Long = 0, var type: String = "", var targetId: String = "", var key: String = "")

