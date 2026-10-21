package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class ReportAiCircuitStatePo(var circuitState:String="",var consecutiveFailures:Int=0)

