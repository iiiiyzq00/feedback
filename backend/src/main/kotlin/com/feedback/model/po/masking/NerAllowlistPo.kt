package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class NerAllowlistPo(
    var id: Long = 0, var term: String = "", var type: String = "", var status: String = "",
    var listVersion: String = "", var reason: String? = null, var lifecycleVersion: Long = 0,
    var createdAt: LocalDateTime? = null, var retiredAt: LocalDateTime? = null
)

