package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricIdentityPo(var sourceId:Long=0,var fingerprint:String="",var identityType:String="",var identityScope:String?=null)

