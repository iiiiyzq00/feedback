package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import java.time.LocalDateTime

data class AlertNotificationRuntimePo(var pendingNotifications:Long=0,var deadLetters:Long=0,var lastDeliveryAt:LocalDateTime?=null)

