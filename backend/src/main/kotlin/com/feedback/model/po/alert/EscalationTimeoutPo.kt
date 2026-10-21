package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationTimeoutPo(var id:Long=0,var displayId:Long=0,var targetOrder:Int=0,var channelOrder:Int=0)

