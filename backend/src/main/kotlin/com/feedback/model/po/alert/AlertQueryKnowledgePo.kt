package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryKnowledgePo(var id:Long=0,var title:String="",var solution:String="",var featureModule:String?=null,var issueType:String?=null,var shownCount:Long=0,var adoptionCount:Long=0,var effectivenessScore:BigDecimal?=null,var publishedAt:LocalDateTime?=null)

