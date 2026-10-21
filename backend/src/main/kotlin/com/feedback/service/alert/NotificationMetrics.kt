package com.feedback.service.alert

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import com.feedback.repository.alert.NotificationMetricsRepository
import java.util.concurrent.TimeUnit

@Service
class NotificationMetrics(private val metrics: NotificationMetricsRepository,private val registry:MeterRegistry){
    init {
        gauge("alert.notification.pending", metrics::pending)
        gauge("alert.notification.failed", metrics::failed)
        gauge("alert.notification.dead_letter", metrics::deadLetterOpen)
        gauge("alert.notification.oldest_pending_seconds", metrics::oldestPendingSeconds)
        gauge("alert.notification.failure_rate_5m", metrics::failureRateFiveMinutes)
        gauge("alert.notification.retry_exhausted", metrics::retryExhausted)
    }
    fun record(channel:String,result:DeliveryResult,durationMs:Long){Timer.builder("alert.notification.delivery.duration").tag("channel",channel).tag("result",if(result.delivered)"delivered" else "failed").register(registry).record(durationMs,TimeUnit.MILLISECONDS)}
    private fun gauge(name:String,value:()->Double){Gauge.builder(name,value).register(registry)}
}
