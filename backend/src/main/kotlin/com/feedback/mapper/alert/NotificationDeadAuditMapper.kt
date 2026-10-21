package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface NotificationDeadAuditMapper{fun insert(@Param("id")id:Long,@Param("channel")channel:String,@Param("error")error:String?,@Param("attempts")attempts:Int):Int}

