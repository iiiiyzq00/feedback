package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class IngestAlertInstancePo(var id:Long=0,var ruleId:Long=0,var feedbackSourceId:Long?=null,var severity:String="P2")

