package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class ClosureSourcePo(var id:Long=0,var sourceChannel:String="",var provinceCode:String?=null,var cityCode:String?=null)

