package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IsolatedAuditInsertPo(var actor:Long=0,var operation:String="",var targetId:String="",var fromStatus:String="",var toStatus:String="",var reason:String="")

