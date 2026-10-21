package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertQueryRcaMapper{fun latest(@Param("incidentId")incidentId:Long,@Param("type")type:String):AlertQueryRcaPo?}

