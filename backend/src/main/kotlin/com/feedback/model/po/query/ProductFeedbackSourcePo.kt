package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackSourcePo(var id:Long=0,var sourceChannel:String="",var provinceCode:String?=null,var cityCode:String?=null)

