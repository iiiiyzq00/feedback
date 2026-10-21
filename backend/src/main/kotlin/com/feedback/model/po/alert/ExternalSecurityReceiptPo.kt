package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ExternalSecurityReceiptPo(var alertInstanceId:Long=0,var incidentId:Long=0)

