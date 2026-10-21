package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper

@Mapper
interface NotificationMetricsMapper {
    fun pending(): Double
    fun failed(): Double
    fun deadLetterOpen(): Double
    fun oldestPendingSeconds(): Double
    fun retryExhausted(): Double
}

