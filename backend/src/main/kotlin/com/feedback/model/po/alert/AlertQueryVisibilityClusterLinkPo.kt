package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryVisibilityClusterLinkPo(var clusterId:Long=0,var sourceId:Long=0)

