package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FaultOccurrenceTimeMyBatisContractTest {
    private val resources = listOf(
        "FaultOccurrenceTimeSourceMapper.xml",
        "FaultOccurrenceTimeContentMapper.xml",
        "FaultOccurrenceTimeJiraMapper.xml",
        "FaultOccurrenceTimeStationMapper.xml",
        "FaultOccurrenceTimeQualityMapper.xml"
    ).map { "mybatis/mapper/normalization/$it" }

    @Test
    fun `source time mapper XML parses and stays single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        resources.forEach { resource ->
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }

        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeSourceMapper.findPendingAfter"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeSourceMapper.updateAll"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeContentMapper.findBySourceIds"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeJiraMapper.findBySourceIds"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeStationMapper.findBySourceIds"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.normalization.FaultOccurrenceTimeQualityMapper.findBySourceIds"))
    }
}
