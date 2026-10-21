package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationNotificationPo(var id:Long=0,var chainId:Long?=null,var targetId:Long?=null,var targetUserId:Long?=null,var channelOrder:Int=0)

