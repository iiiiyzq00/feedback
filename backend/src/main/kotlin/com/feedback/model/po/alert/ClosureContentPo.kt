package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class ClosureContentPo(var sourceId:Long=0,var functionModule:String?=null,var processingStatus:String="")

