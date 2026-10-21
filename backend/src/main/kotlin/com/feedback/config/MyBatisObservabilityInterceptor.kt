package com.feedback.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.apache.ibatis.cache.CacheKey
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.BoundSql
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.plugin.Interceptor
import org.apache.ibatis.plugin.Intercepts
import org.apache.ibatis.plugin.Invocation
import org.apache.ibatis.plugin.Signature
import org.apache.ibatis.session.ResultHandler
import org.apache.ibatis.session.RowBounds
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * 记录基数有界的 MyBatis 语句遥测，不打印 SQL、参数与结果内容。
 * 请求 ID 只通过 MDC 出现在慢查询/失败日志里，绝不作为指标标签。
 */
@Component
@Intercepts(
    Signature(type = Executor::class, method = "update", args = [MappedStatement::class, Any::class]),
    Signature(
        type = Executor::class,
        method = "query",
        args = [MappedStatement::class, Any::class, RowBounds::class, ResultHandler::class]
    ),
    Signature(
        type = Executor::class,
        method = "query",
        args = [MappedStatement::class, Any::class, RowBounds::class, ResultHandler::class, CacheKey::class, BoundSql::class]
    )
)
class MyBatisObservabilityInterceptor(
    private val meters: MeterRegistry,
    private val properties: MyBatisObservabilityProperties
) : Interceptor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun intercept(invocation: Invocation): Any? {
        if (!properties.enabled) return invocation.proceed()
        val statement = invocation.args[0] as MappedStatement
        val statementId = statement.id
        val command = statement.sqlCommandType.name.lowercase()
        val started = System.nanoTime()
        return try {
            invocation.proceed().also { result ->
                record(statementId, command, "success", System.nanoTime() - started, rowCount(result))
            }
        } catch (error: Throwable) {
            val elapsed = System.nanoTime() - started
            val failure = classify(error)
            record(statementId, command, "failure", elapsed, null)
            Counter.builder("mybatis.statement.failures")
                .description("MyBatis statement failures by safe database category")
                .tag("statement", statementId)
                .tag("command", command)
                .tag("category", failure)
                .register(meters)
                .increment()
            logger.warn(
                "MyBatis statement failed: statementId={}, command={}, durationMs={}, category={}, requestId={}",
                statementId, command, nanosToMillis(elapsed), failure, requestId()
            )
            throw error
        }
    }

    private fun record(statementId: String, command: String, outcome: String, elapsed: Long, rows: Long?) {
        Timer.builder("mybatis.statement.duration")
            .description("MyBatis statement execution time")
            .tag("statement", statementId)
            .tag("command", command)
            .tag("outcome", outcome)
            .register(meters)
            .record(elapsed, TimeUnit.NANOSECONDS)
        rows?.let {
            DistributionSummary.builder("mybatis.statement.rows")
                .description("Rows returned or affected by MyBatis statements")
                .tag("statement", statementId)
                .tag("command", command)
                .register(meters)
                .record(it.toDouble())
        }
        if (outcome == "success" && nanosToMillis(elapsed) >= properties.slowStatementThresholdMs) {
            logger.warn(
                "Slow MyBatis statement: statementId={}, command={}, durationMs={}, outcome={}, rows={}, requestId={}",
                statementId, command, nanosToMillis(elapsed), outcome, rows, requestId()
            )
        }
    }

    private fun rowCount(result: Any?): Long? = when (result) {
        is Collection<*> -> result.size.toLong()
        is Number -> result.toLong()
        else -> null
    }

    private fun classify(error: Throwable): String {
        val sql = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            ?: return "other"
        return when {
            sql.errorCode == 1213 || sql.sqlState == "40001" -> "deadlock"
            sql.errorCode == 1205 || sql.sqlState == "41000" -> "lock_timeout"
            else -> "sql_error"
        }
    }

    private fun nanosToMillis(value: Long): Long = TimeUnit.NANOSECONDS.toMillis(value)
    private fun requestId(): String = MDC.get("requestId") ?: "background"
}
