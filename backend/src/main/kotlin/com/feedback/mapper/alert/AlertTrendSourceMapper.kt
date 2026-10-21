package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertTrendSourceMapper{fun findIds(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime,@Param("afterId")afterId:Long,@Param("limit")limit:Int):List<Long>}

