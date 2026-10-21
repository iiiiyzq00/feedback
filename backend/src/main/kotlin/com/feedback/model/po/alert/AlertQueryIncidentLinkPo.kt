package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryIncidentLinkPo(var id:Long=0,var alertInstanceId:Long=0,var incidentId:Long=0)

