package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReviewSlaPo(var scopeKey:String="",var subtype:String="",var minutes:Long=0)

