package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppProductRequestPo(var id: Long = 0, var requestNo: String = "", var title: String = "", var createdBy: Long = 0)

