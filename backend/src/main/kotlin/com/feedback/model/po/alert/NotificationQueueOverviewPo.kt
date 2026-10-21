package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class NotificationQueueOverviewPo(var pending:Long=0,var oldestPendingSeconds:Long=0,var openDeadLetters:Long=0)

