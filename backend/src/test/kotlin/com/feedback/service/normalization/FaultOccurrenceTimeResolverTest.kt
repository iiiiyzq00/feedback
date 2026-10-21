package com.feedback.service.normalization

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FaultOccurrenceTimeResolverTest {
    private val reference=LocalDateTime.of(2026,8,20,10,0)

    @Test fun `parses jira hyphen separated timestamp preserved by masking`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】:2025-06-24-18-42-30-01 8、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2025,6,24,18,42,30),fact.occurredAt)
        assertEquals("SECOND",fact.precision)
        assertEquals("RESOLVED",fact.resolution)
    }

    @Test fun `extracts full Jira fault time instead of issue creation time`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","7、【出现故障时间(精确到分)】:2026-08-17 18:26:39 8、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2026,8,17,18,26,39),fact.occurredAt)
        assertEquals("SECOND",fact.precision)
        assertEquals("JIRA_DESCRIPTION",fact.source)
    }

    @Test fun `accepts whitespace inside governed Jira fault-time label`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","5、【出现故障时间(精确到\n分)】：2026-08-17 18:26:39 6、【是否必现】：是",reference)
        assertEquals(LocalDateTime.of(2026,8,17,18,26,39),fact.occurredAt)
    }

    @Test fun `infers year for month-day time from Jira creation context`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】:08-19 18:36 8、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2026,8,19,18,36),fact.occurredAt)
        assertEquals("MINUTE",fact.precision)
    }

    @Test fun `does not mistake range start minute for a short year`() {
        val created=LocalDateTime.of(2025,5,24,12,12)
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA",
            "【出现故障时间(精确到分)】:05-23 19:59-05-23 20:35 8、【是否必现】:是",created)
        assertEquals(LocalDateTime.of(2025,5,23,19,59),fact.occurredAt)
        assertEquals("RANGE",fact.precision)
        assertEquals("RESOLVED",fact.resolution)
    }

    @Test fun `uses Jira creation year for a second month-day range`() {
        val created=LocalDateTime.of(2023,10,14,13,40)
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA",
            "【出现故障时间(精确到分)】:10-14 08:57-10-14 09:55 8、【是否必现】:是",created)
        assertEquals(LocalDateTime.of(2023,10,14,8,57),fact.occurredAt)
        assertEquals("RANGE",fact.precision)
    }

    @Test fun `keeps a plausible historical two digit year`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA",
            "【出现故障时间(精确到分)】:25-05-23 20:35 8、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2025,5,23,20,35),fact.occurredAt)
    }

    @Test fun `never resolves a future short year beyond Jira reference`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA",
            "【出现故障时间(精确到分)】:59-05-23 20:35 8、【是否必现】:是",reference)
        assertNull(fact.occurredAt)
        assertEquals("UNPARSEABLE",fact.resolution)
    }

    @Test fun `parses Chinese hour without partially matching the day`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】:2026年8月20日九点 8、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2026,8,20,9,0),fact.occurredAt)
        assertEquals("HOUR",fact.precision)
    }

    @Test fun `fills ticket year and month for day-only evening range`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","5、【出现故障时间(精确到分)】:18号晚上5点到7点左右 6、【是否必现】:是",reference)
        assertEquals(LocalDateTime.of(2026,8,18,17,0),fact.occurredAt)
        assertEquals("RANGE",fact.precision)
        assertEquals("18号晚上5点到7点左右",fact.raw)
    }

    @Test fun `uses previous day when an evening time is later than ticket creation`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】：晚上5点左右 6、【是否必现】：是",reference)
        assertEquals(LocalDateTime.of(2026,8,19,17,0),fact.occurredAt)
        assertEquals("HOUR",fact.precision)
    }

    @Test fun `uses the nearest non-future date for a numeric time only`() {
        val afterCreation=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】：18:28 6、【是否必现】：是",reference)
        val beforeCreation=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】：08:28 6、【是否必现】：是",reference)
        assertEquals(LocalDateTime.of(2026,8,19,18,28),afterCreation.occurredAt)
        assertEquals(LocalDateTime.of(2026,8,20,8,28),beforeCreation.occurredAt)
        assertEquals("MINUTE",afterCreation.precision)
    }

    @Test fun `does not substitute creation time when source fault time is missing`() {
        val fact=FaultOccurrenceTimeResolver.fromDescription("JIRA","【出现故障时间(精确到分)】: 8、【是否必现】:是",reference)
        assertNull(fact.occurredAt)
        assertEquals("MISSING",fact.resolution)
    }

    @Test fun `redblack publication is explicitly not a fault occurrence`() {
        assertEquals("NOT_APPLICABLE",FaultOccurrenceTimeResolver.notApplicable().resolution)
    }

    @Test fun `proxy facts retain an explicit non-authoritative source`() {
        val fact=FaultOccurrenceTimeResolver.proxy(reference,"QUALITY_RETURN_APPLY_PROXY","DAY")
        assertEquals(reference,fact.occurredAt)
        assertEquals("QUALITY_RETURN_APPLY_PROXY",fact.source)
        assertEquals("DAY",fact.precision)
    }
}
