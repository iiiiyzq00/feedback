package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertProductionRuleMapper{fun find(@Param("id")id:Long):GovernanceRulePo?;fun lock(@Param("id")id:Long):GovernanceRulePo?;fun retireActive(@Param("code")code:String):Int;fun activate(@Param("id")id:Long,@Param("enabled")enabled:Boolean):Int;fun disable(@Param("id")id:Long):Int}

