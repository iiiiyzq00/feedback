package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SkillPromptMyBatisContractTest {
    private val mappers = mapOf(
        "SkillPromptMapper.xml" to "t_ai_task",
        "PromptFeedbackStatsMapper.xml" to "t_feedback_ai"
    )

    @Test
    fun `skill and prompt mapper XML parses and stays single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mappers.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/ai/$name"
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
}
