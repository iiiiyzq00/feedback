package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraOwnerInitializationMyBatisContractTest {
    private val mapperTables = mapOf(
        "JiraOwnerSourceMapper.xml" to "t_feedback_source",
        "JiraOwnerAssigneeMapper.xml" to "t_channel_jira",
        "JiraOwnerWorkflowMapper.xml" to "t_feedback_workflow_state",
        "JiraOwnerAssignmentMapper.xml" to "t_feedback_assignment",
        "JiraOwnerActiveUserMapper.xml" to "t_auth_user",
        "JiraOwnerDispositionEventMapper.xml" to "t_feedback_disposition_event"
    )

    @Test
    fun `Jira owner mapper XML parses and every statement remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mapperTables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/jira/owner/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            assertFalse(Regex("\\bUNION\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val referencedTables = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .map { it.value.lowercase() }
                .toSet()
            assertTrue(referencedTables == setOf(expectedTable), "$name:$referencedTables")
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.owner.JiraOwnerAssignmentMapper.insertBatch"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.owner.JiraOwnerWorkflowMapper.acceptBatch"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.owner.JiraOwnerDispositionEventMapper.insertBatch"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.owner.JiraOwnerActiveUserMapper.resolveDisplayNameKeys"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.jira.owner.JiraOwnerActiveUserMapper.findActiveByMatchKeys"))
    }

    @Test
    fun `Jira owner service and runner contain no direct SQL JDBC or JPA access`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/feedback/service/jira/JiraAssigneeOwnerInitializer.kt")
        )
        listOf("JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager", "JpaRepository").forEach { forbidden ->
            assertFalse(source.contains(forbidden), forbidden)
        }
        assertFalse(Regex("\\b(SELECT|INSERT|UPDATE|DELETE)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(source))
    }
}
