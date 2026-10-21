package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SdkMaterializationMyBatisContractTest {
    private val mapperTables = mapOf(
        "SdkProcessingEnvelopeMapper.xml" to "t_sdk_ingest",
        "SdkFeedbackSourceMutationMapper.xml" to "t_feedback_source",
        "SdkFeedbackContentMutationMapper.xml" to "t_feedback_content",
        "SdkChannelMutationMapper.xml" to "t_channel_sdk",
        "SdkIdentityMutationMapper.xml" to "t_feedback_identity",
        "SdkAttachmentMutationMapper.xml" to "t_feedback_attachment",
        "SdkAuthorizedContactMutationMapper.xml" to "t_authorized_contact_vault",
        "SdkTransportTelemetryMapper.xml" to "t_system_audit_log",
        "SdkRequestMetricMapper.xml" to "t_alert_occurrence"
    )

    @Test
    fun `SDK materialization mapper XML parses and every statement remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mapperTables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/sdk/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val referencedTables = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .map { it.value.lowercase() }
                .toSet()
            assertTrue(referencedTables == setOf(expectedTable), "$name:$referencedTables")
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.sdk.SdkFeedbackSourceMutationMapper.insert"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.sdk.SdkChannelMutationMapper.upsertControlledDiagnostics"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.sdk.SdkIdentityMutationMapper.upsertBatch"))
    }

    @Test
    fun `SDK services contain no direct SQL JDBC or JPA repository access`() {
        listOf(
            "src/main/kotlin/com/feedback/service/masking/SdkSecureProcessingService.kt",
            "src/main/kotlin/com/feedback/service/masking/SdkSecureProcessingScheduler.kt",
            "src/main/kotlin/com/feedback/service/masking/SdkControlledDiagnosticBackfillRunner.kt",
            "src/main/kotlin/com/feedback/service/impl/sdk/SdkIngestServiceImpl.kt",
            "src/main/kotlin/com/feedback/manager/sdk/SdkRequestTelemetryRecorder.kt"
        ).forEach { file ->
            val source = Files.readString(Path.of(file))
            listOf("JdbcTemplate", "NamedParameterJdbcTemplate", "JpaRepository")
                .forEach { forbidden -> assertFalse(source.contains(forbidden), "$file:$forbidden") }
            assertFalse(Regex("\\b(SELECT|INSERT|UPDATE|DELETE)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(source), file)
        }
    }
}
