package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemConfigMyBatisContractTest {
    private val resource = "mybatis/mapper/config/SystemConfigMapper.xml"

    @Test
    fun `system config mapper parses and every statement stays on one table`() {
        val xml = Resources.getResourceAsReader(resource).use { it.readText() }
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml))
        assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml))

        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        Resources.getResourceAsReader(resource).use { reader ->
            XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
        }

        listOf(
            "findActiveCatalogs", "findCatalog", "lockCatalogs", "findActiveConfigurations",
            "findHistory", "countHistory", "countHistoryByKeys", "deactivate", "insertAll",
            "findByKeyAndVersion", "findActiveRuntime", "findActiveMaskingRuleVersion",
            "findMaskingRelease", "findMaskingEvidence"
        ).forEach { statement ->
            assertTrue(configuration.hasStatement("com.feedback.mapper.config.SystemConfigMapper.$statement"))
        }
    }
}
