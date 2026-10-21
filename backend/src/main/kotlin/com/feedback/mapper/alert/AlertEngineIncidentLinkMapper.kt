package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertEngineIncidentLinkMapper{fun insert(@Param("incident")incident:Long,@Param("instance")instance:Long):Int;fun activeIncident(@Param("instance")instance:Long):Long?}

