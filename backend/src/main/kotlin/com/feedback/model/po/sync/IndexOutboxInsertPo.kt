package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IndexOutboxInsertPo(val sourceId: Long?, val taskKey: String, val inputSnapshot: String)

