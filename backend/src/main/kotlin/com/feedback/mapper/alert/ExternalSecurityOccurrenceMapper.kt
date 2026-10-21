package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ExternalSecurityOccurrenceMapper{fun insert(@Param("alertId")alertId:Long,@Param("evidenceJson")evidenceJson:String,@Param("occurredAt")occurredAt:LocalDateTime):Int}

