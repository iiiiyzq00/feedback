package com.feedback.repository.alert

import com.feedback.mapper.alert.NotificationMetricsMapper
import com.feedback.mapper.alert.NotificationOccurrenceMetricsMapper
import org.springframework.stereotype.Repository

@Repository
class NotificationMetricsRepository(private val mapper: NotificationMetricsMapper, private val occurrences: NotificationOccurrenceMetricsMapper) {
    fun pending() = mapper.pending()
    fun failed() = mapper.failed()
    fun deadLetterOpen() = mapper.deadLetterOpen()
    fun oldestPendingSeconds() = mapper.oldestPendingSeconds()
    fun failureRateFiveMinutes() = occurrences.failureRateFiveMinutes()
    fun retryExhausted() = mapper.retryExhausted()
}
