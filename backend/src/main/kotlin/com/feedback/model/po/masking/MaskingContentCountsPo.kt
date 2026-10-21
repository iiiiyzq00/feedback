package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class MaskingContentCountsPo(
    var sourceRecords: Long = 0, var safeRecords: Long = 0, var technicalFailures: Long = 0
)

