package com.feedback.testinfra

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID

/** 固定时钟、种子与 ID，保证四渠道业务夹具可复现。 */
class TestDataFixtures(
    val seed: Long = 20260804L,
    val clock: Clock = Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC)
) {
    private val random = Random(seed)
    private var sequence = 0L

    fun nextId(prefix: String): String = "$prefix-${sequence++.toString().padStart(6, '0')}"

    fun deterministicUuid(scope: String): UUID = UUID.nameUUIDFromBytes("$seed:$scope".toByteArray())

    fun sdk(overrides: Map<String, Any?> = emptyMap()) = record("SDK", mapOf(
        "source_record_id" to nextId("SDK"), "package_name" to "com.company.tutor",
        "function_module" to "AI_TUTOR", "severity_level" to "S1",
        "source_event_time" to clock.instant().toString(), "description" to "AI助教打开后闪退"
    ), overrides)

    fun jira(overrides: Map<String, Any?> = emptyMap()) = record("JIRA", mapOf(
        "source_record_id" to nextId("JIRA"), "project_key" to "DEMO", "status" to "Open",
        "source_event_time" to clock.instant().toString(), "description" to "AI助教启动崩溃"
    ), overrides)

    fun quality(overrides: Map<String, Any?> = emptyMap()) = record("QUALITY", mapOf(
        "source_record_id" to nextId("QUALITY"), "repair_region" to "华东", "application_type" to "维修",
        "source_event_time" to clock.instant().toString(), "description" to "模拟设备出现质量缺陷"
    ), overrides)

    fun redblack(overrides: Map<String, Any?> = emptyMap()) = record("REDBLACK", mapOf(
        "source_record_id" to nextId("REDBLACK"), "emotion" to "黑榜", "source_platform" to "模拟平台",
        "source_event_time" to clock.instant().toString(), "description" to "模拟黑榜功能异常"
    ), overrides)

    fun malformed(channel: String) = record(channel, mapOf(
        "source_record_id" to nextId("BAD"), "source_event_time" to "not-a-date", "description" to "测试错误数据"
    ), emptyMap())

    fun duplicate(record: FixtureRecord): FixtureRecord = record.copy(attributes = record.attributes.toMap())

    fun randomSuffix(): Int = random.nextInt(10_000)

    private fun record(channel: String, base: Map<String, Any?>, overrides: Map<String, Any?>) =
        FixtureRecord(channel, base + overrides)
}

data class FixtureRecord(val channel: String, val attributes: Map<String, Any?>)
