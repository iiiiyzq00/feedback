package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DataContractPo(var contractCode:String="",var sourceSystem:String="",var schemaVersion:String="")

