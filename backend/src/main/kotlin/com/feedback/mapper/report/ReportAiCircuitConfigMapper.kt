package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ReportAiCircuitConfigMapper{fun ensure():Int;fun lock():ReportAiCircuitStatePo;fun probeDue():Boolean?;fun halfOpen():Int;fun close():Int;fun failure(@Param("failures")failures:Int,@Param("code")code:String,@Param("state")state:String,@Param("open")open:Boolean):Int}

