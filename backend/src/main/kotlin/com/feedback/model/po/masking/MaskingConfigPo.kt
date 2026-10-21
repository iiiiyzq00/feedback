package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MaskingConfigPo(
    var id:Long=0,
    var configKey:String="",
    var configType:String?=null,
    var configSubtype:String?=null,
    var configVersion:String="",
    var configValue:String="{}",
    var lifecycleStatus:String?=null,
    var approvalStatus:String?=null,
    var scopeKey:String?=null,
    var recordStatus:String?=null,
    var recordVersion:Long?=null,
    var subjectUserId:Long?=null,
    var configDesc:String?=null,
    var isActive:Boolean=false,
    var createdAt:LocalDateTime?=null,
    var effectiveFrom:LocalDateTime?=null,
    var effectiveTo:LocalDateTime?=null
)

