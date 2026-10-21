package com.feedback.testinfra

import java.time.Duration
import java.util.concurrent.locks.LockSupport

class AwaitTimeoutException(message: String, cause: Throwable? = null) : AssertionError(message, cause)

/** 基于截止时间的轮询，退避有界，超时时给出诊断状态。 */
object Eventually {
    fun <T> await(
        description: String,
        timeout: Duration = Duration.ofSeconds(10),
        initialInterval: Duration = Duration.ofMillis(20),
        maxInterval: Duration = Duration.ofMillis(250),
        diagnostics: () -> String = { "no diagnostics supplied" },
        probe: () -> T?,
        accepted: (T) -> Boolean = { true }
    ): T {
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        val deadline = System.nanoTime() + timeout.toNanos()
        var interval = initialInterval.toNanos().coerceAtLeast(1)
        var attempts = 0
        var lastValue: T? = null
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            attempts++
            try {
                val value = probe()
                lastValue = value
                if (value != null && accepted(value)) return value
            } catch (error: Throwable) {
                lastError = error
            }
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) LockSupport.parkNanos(minOf(interval, remaining))
            interval = minOf(interval * 2, maxInterval.toNanos())
        }
        throw AwaitTimeoutException(
            "Timed out awaiting '$description' after ${timeout.toMillis()}ms; attempts=$attempts; " +
                "lastValue=$lastValue; lastError=${lastError?.message}; state=${diagnostics()}", lastError
        )
    }
}
