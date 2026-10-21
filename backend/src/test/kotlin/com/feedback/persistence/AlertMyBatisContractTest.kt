package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlertMyBatisContractTest {
    private val mappers = mapOf(
        "AlertRuleManagementMapper.xml" to "t_alert_rule",
        "AlertRuleInstanceMapper.xml" to "t_alert_instance",
        "NotificationDispatchMapper.xml" to "t_alert_notification",
        "NotificationDigestInstanceMapper.xml" to "t_alert_instance",
        "NotificationMetricsMapper.xml" to "t_alert_notification",
        "NotificationOccurrenceMetricsMapper.xml" to "t_alert_occurrence",
    )

    @Test
    fun `alert mapper XML parses and each mapper stays on one physical table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mappers.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/alert/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\b(JOIN|EXISTS|UNION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("\\b(?:FROM|UPDATE|INTO|DELETE\\s+FROM)\\s+(t_[a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(setOf(expectedTable), tables, name)
            Resources.getResourceAsReader(resource).use {
                XMLMapperBuilder(it, configuration, resource, configuration.sqlFragments).parse()
            }
        }
    }

    @Test
    fun `provider receipts lock notification and order audit facts by provider event time`() {
        val notificationXml=Resources.getResourceAsReader("mybatis/mapper/alert/NotificationProviderReceiptNotificationMapper.xml").use{it.readText()}
        val auditXml=Resources.getResourceAsReader("mybatis/mapper/alert/NotificationProviderReceiptAuditMapper.xml").use{it.readText()}

        assertTrue(notificationXml.contains("LIMIT 1 FOR UPDATE"))
        assertTrue(auditXml.contains("ORDER BY event_time DESC,id DESC LIMIT 1"))
    }

    @Test
    fun `jira alert cursor uses the channel shared primary key`() {
        val xml = Resources.getResourceAsReader("mybatis/mapper/alert/AlertMetricJiraMapper.xml").use { it.readText() }

        assertTrue(xml.contains("source_id AS raw_id"))
        assertTrue(xml.contains("source_id &gt; #{after}"))
        assertTrue(xml.contains("ORDER BY source_id"))
        assertFalse(Regex("""\bid\s*(?:AS\s+raw_id|&gt;|ORDER BY)""", RegexOption.IGNORE_CASE).containsMatchIn(xml))
    }

    @Test
    fun `escalation timeout and target transitions use current state compare and set`() {
        val xml=Resources.getResourceAsReader("mybatis/mapper/alert/NotificationEscalationMapper.xml").use{it.readText()}

        assertTrue(xml.contains("<update id=\"advanceTimedOut\">"))
        assertTrue(xml.contains("ack_deadline IS NOT NULL AND ack_deadline&lt;=NOW(3)"))
        assertTrue(xml.contains("target_order=#{row.targetOrder} AND channel_sequence=#{row.channelOrder}"))
        assertTrue(xml.contains("escalation_status='RUNNING' AND target_order=#{target} AND channel_sequence=#{channel}"))
    }

    @Test
    fun `escalation acknowledgement statements constrain target and notification record types`() {
        val xml=Resources.getResourceAsReader("mybatis/mapper/alert/NotificationEscalationMapper.xml").use{it.readText()}

        assertTrue(xml.contains("record_type='ESCALATION_TARGET' AND escalation_status IN ('PENDING','IN_PROGRESS','FAILED')"))
        assertTrue(xml.contains("record_type='NOTIFICATION' AND escalation_chain_id IS NOT NULL"))
        assertTrue(xml.contains("record_type='ESCALATION_CHAIN' AND alert_instance_id=#{alert} AND escalation_status='RUNNING'"))
        assertTrue(xml.contains("WHERE id=#{id} AND record_type='NOTIFICATION'"))
    }
}
