package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EscalationAuditMapper{fun exhausted(@Param("id")id:Long,@Param("code")code:String,@Param("team")team:String?):Int}

