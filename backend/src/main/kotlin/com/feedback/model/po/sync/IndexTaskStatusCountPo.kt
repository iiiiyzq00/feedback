package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IndexTaskStatusCountPo(var status: String = "", var count: Long = 0)

