package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraIngestionMyBatisContractTest {
    private val tables = mapOf(
        "JiraSyncStateMapper.xml" to "t_jira_sync_state",
        "JiraAttachmentTaskMapper.xml" to "t_jira_attachment_pull_task",
        "JiraFeedbackSourceLookupMapper.xml" to "t_feedback_source"
    )

    @Test fun `Jira mapper XML parses and each mapper remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        tables.forEach { (name, table) ->
            val resource = "mybatis/mapper/jira/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val referenced = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE).findAll(xml)
                .map { it.value.lowercase() }.toSet()
            assertTrue(referenced == setOf(table), "$name:$referenced")
            Resources.getResourceAsReader(resource).use { XMLMapperBuilder(it, configuration, resource, configuration.sqlFragments).parse() }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.JiraAttachmentTaskMapper.findClaimableIds"))
    }

    @Test fun `Jira ingest services contain no direct JDBC JPA or business SQL`() {
        listOf(
            Path.of("src/main/kotlin/com/feedback/repository/jira/JiraSyncStateRepository.kt"),
            Path.of("src/main/kotlin/com/feedback/service/jira/JiraAttachmentPullQueue.kt"),
            Path.of("src/main/kotlin/com/feedback/service/jira/JiraLogAttachmentImportService.kt")
        ).forEach { path ->
            val source = Files.readString(path)
            listOf("JdbcTemplate","NamedParameterJdbcTemplate","JpaRepository","FeedbackAttachmentRepository")
                .forEach { assertFalse(source.contains(it), "$path:$it") }
            assertFalse(Regex("\\b(SELECT.+FROM|INSERT\\s+INTO|UPDATE\\s+t_|DELETE\\s+FROM)\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(source), path.toString())
        }
    }
}
