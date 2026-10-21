package com.feedback.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiObservabilityFilter(private val meters: MeterRegistry,private val sdkTelemetry:SdkRequestTelemetryRecorder) : OncePerRequestFilter() {
    private val validRequestId = Regex("^[A-Za-z0-9_-]{8,64}$")

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = request.getHeader("X-Request-Id")?.takeIf(validRequestId::matches) ?: UUID.randomUUID().toString()
        val started = System.nanoTime()
        MDC.put("requestId", requestId)
        request.setAttribute("requestId", requestId)
        response.setHeader("X-Request-Id", requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            val durationNanos = System.nanoTime() - started
            val durationMs = durationNanos / 1_000_000
            response.setHeader("Server-Timing", "app;dur=$durationMs")
            val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: "unmatched"
            Timer.builder("http.api.request.duration")
                .description("API request processing time")
                .tag("method", request.method)
                .tag("route", route)
                .tag("status", statusGroup(response.status))
                .publishPercentileHistogram()
                .register(meters)
                .record(durationNanos, TimeUnit.NANOSECONDS)
            if (response.status == 429 || response.status == 503) response.setHeader("Retry-After", "3")
            if(request.requestURI=="/api/v1/sdk/feedback"&&request.method.equals("POST",true)){
                sdkTelemetry.record(request.getHeader("X-App-Key"),response.status,request.getAttribute("sdkErrorCode")?.toString(),durationMs)
            }
            MDC.remove("requestId")
        }
    }

    private fun statusGroup(status: Int) = "${status / 100}xx"
}
