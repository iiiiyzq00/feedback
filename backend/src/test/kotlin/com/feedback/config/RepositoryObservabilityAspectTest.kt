package com.feedback.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RepositoryObservabilityAspectTest {
    private val meters = SimpleMeterRegistry()
    private val aspect = RepositoryObservabilityAspect(meters)

    @Test
    fun `repository success records one method timer without argument tags`() {
        val joinPoint = joinPoint("find")
        val result = Any()
        every { joinPoint.proceed() } returns result

        assertSame(result, aspect.record(joinPoint))

        val timer = meters.get("repository.method.duration")
            .tags("repository", "SampleRepository", "method", "find", "outcome", "success")
            .timer()
        assertEquals(1, timer.count())
        assertEquals(null, timer.id.getTag("arguments"))
    }

    @Test
    fun `repository failure is timed and propagated`() {
        val joinPoint = joinPoint("find")
        val failure = IllegalStateException("FAILED")
        every { joinPoint.proceed() } throws failure

        assertSame(failure, assertThrows(IllegalStateException::class.java) { aspect.record(joinPoint) })
        assertEquals(
            1,
            meters.get("repository.method.duration")
                .tags("repository", "SampleRepository", "method", "find", "outcome", "failure")
                .timer().count()
        )
    }

    private fun joinPoint(methodName: String): ProceedingJoinPoint {
        val signature = mockk<MethodSignature>()
        every { signature.declaringType } returns SampleRepository::class.java
        every { signature.method } returns SampleRepository::class.java.getDeclaredMethod(methodName)
        return mockk { every { this@mockk.signature } returns signature }
    }

    private class SampleRepository {
        fun find() = Unit
    }
}
