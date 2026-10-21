package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EscalationIncidentMapper{fun severity(@Param("id")id:Long,@Param("severity")severity:String):Int;fun reason(@Param("id")id:Long,@Param("reason")reason:String):Int}

