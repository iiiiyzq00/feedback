package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class DistributionOwnerPo(var feedbackSourceId:Long=0,var ownerUserId:Long=0)

