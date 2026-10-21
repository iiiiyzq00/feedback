package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertQueryAuditMapper{fun timeline(@Param("id")id:String):List<AlertQueryAuditTimelinePo>;fun insert(@Param("actor")actor:Long,@Param("operation")operation:String,@Param("id")id:String,@Param("detail")detail:String):Int}

