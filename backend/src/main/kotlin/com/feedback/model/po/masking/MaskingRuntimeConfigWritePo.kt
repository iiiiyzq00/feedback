package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MaskingRuntimeConfigWritePo(
    var configKey:String="",
    var configVersion:String="",
    var configValue:String="{}",
    var configDesc:String="",
    var createdBy:Long=0
)

