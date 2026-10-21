package com.feedback.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/** 记录 Repository 边界耗时，且不把参数、返回值或请求标识作为标签。 */
@Aspect
@Component
class RepositoryObservabilityAspect(private val meters: MeterRegistry) {
    @Around("@within(org.springframework.stereotype.Repository)")
    fun record(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val repository = signature.declaringType.simpleName
        val method = signature.method.name
        val started = System.nanoTime()
        var outcome = "success"
        return try {
            joinPoint.proceed()
        } catch (error: Throwable) {
            outcome = "failure"
            throw error
        } finally {
            Timer.builder("repository.method.duration")
                .description("Repository boundary execution time")
                .tag("repository", repository)
                .tag("method", method)
                .tag("outcome", outcome)
                .register(meters)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
        }
    }
}
