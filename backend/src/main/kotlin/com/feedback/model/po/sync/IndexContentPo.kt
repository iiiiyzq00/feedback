package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IndexContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var maskingStatus: String = "PENDING",
    var isDeleted: Boolean = false,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

