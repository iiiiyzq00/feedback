package com.feedback.service.query

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import java.time.LocalDateTime

/** 处理链路汇总页与其下钻页共用的口径边界。 */
object ProcessingChainTimeRange {
    val supported = setOf("today", "24h", "7d")

    fun start(range: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        if (range !in supported) throw ClientException(ErrorCode.BAD_REQUEST, "OPERATIONS_RANGE_INVALID")
        return when (range) {
            "today" -> now.toLocalDate().atStartOfDay()
            "24h" -> now.minusHours(24)
            else -> now.minusDays(7)
        }
    }
}
