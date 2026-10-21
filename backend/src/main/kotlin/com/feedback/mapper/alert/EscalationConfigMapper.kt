package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EscalationConfigMapper{fun targets(@Param("team")team:String):List<EscalationConfigTargetPo>}

