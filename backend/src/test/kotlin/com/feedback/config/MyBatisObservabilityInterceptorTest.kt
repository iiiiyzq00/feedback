package com.feedback.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.mapping.SqlCommandType
import org.apache.ibatis.plugin.Invocation
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException

class MyBatisObservabilityInterceptorTest {
    private val meters = SimpleMeterRegistry()
    private val properties = MyBatisObservabilityProperties().apply { slowStatementThresholdMs = 600_000 }
    private val interceptor = MyBatisObservabilityInterceptor(meters, properties)

    @Test
    fun `query records duration call count and returned rows without SQL or parameter tags`() {
        val invocation = invocation(statement("com.feedback.mapper.query.SampleMapper.find", SqlCommandType.SELECT))
        val rows = listOf("a", "b")
        every { invocation.proceed() } returns rows

        assertSame(rows, interceptor.intercept(invocation))

        val timer = meters.get("mybatis.statement.duration")
            .tags("statement", "com.feedback.mapper.query.SampleMapper.find", "command", "select", "outcome", "success")
            .timer()
        val summary = meters.get("mybatis.statement.rows")
            .tags("statement", "com.feedback.mapper.query.SampleMapper.find", "command", "select")
            .summary()
        assertEquals(1, timer.count())
        assertEquals(2.0, summary.totalAmount())
        assertEquals(null, timer.id.getTag("sql"))
        assertEquals(null, timer.id.getTag("parameters"))
    }

    @Test
    fun `deadlock is classified without swallowing the original exception`() {
        val invocation = invocation(statement("com.feedback.mapper.alert.AlertMapper.lock", SqlCommandType.SELECT))
        val failure = SQLException("redacted by driver", "40001", 1213)
        every { invocation.proceed() } throws failure

        assertSame(failure, assertThrows(SQLException::class.java) { interceptor.intercept(invocation) })

        assertEquals(
            1.0,
            meters.get("mybatis.statement.failures")
                .tags(
                    "statement", "com.feedback.mapper.alert.AlertMapper.lock",
                    "command", "select",
                    "category", "deadlock"
                )
                .counter().count()
        )
    }

    @Test
    fun `disabled observability proceeds without registering statement meters`() {
        properties.enabled = false
        val invocation = invocation(statement("com.feedback.mapper.query.SampleMapper.find", SqlCommandType.SELECT))
        every { invocation.proceed() } returns emptyList<Any>()

        interceptor.intercept(invocation)

        verify(exactly = 1) { invocation.proceed() }
        assertEquals(emptyList<Any>(), meters.meters)
    }

    private fun invocation(statement: MappedStatement): Invocation = mockk {
        every { args } returns arrayOf(statement)
    }

    private fun statement(id: String, command: SqlCommandType): MappedStatement =
        MappedStatement.Builder(Configuration(), id, { null }, command).build()
}
