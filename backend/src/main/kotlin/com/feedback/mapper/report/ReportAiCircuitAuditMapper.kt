package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ReportAiCircuitAuditMapper{fun insert(@Param("operation")operation:String,@Param("detail")detail:String):Int}

