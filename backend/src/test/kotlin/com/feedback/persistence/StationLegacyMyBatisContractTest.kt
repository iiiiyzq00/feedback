package com.feedback.persistence

import com.feedback.repository.station.legacy.StationLegacyRepository
import com.feedback.repository.impl.station.legacy.MyBatisStationLegacyRepositoryImpl
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StationLegacyMyBatisContractTest {
    private val mapperTables = mapOf(
        "StationLegacySourceMapper.xml" to "t_feedback_source",
        "StationLegacyContentMapper.xml" to "t_feedback_content",
        "StationLegacyChannelMapper.xml" to "t_channel_station_feedback",
        "StationLegacyRawPayloadMapper.xml" to "t_station_feedback_raw",
        "StationLegacyIdentityMapper.xml" to "t_feedback_identity",
        "StationLegacyAttachmentMapper.xml" to "t_feedback_attachment"
    )

    @Test
    fun `Legacy mapper XML parses and every statement stays on one table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mapperTables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/station/legacy/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE)
                .findAll(xml).map { it.value.lowercase() }.toSet()
            assertTrue(tables == setOf(expectedTable), "$name:$tables")
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
    }

    @Test
    fun `Legacy service paths contain no JDBC SQL JPA or destructive replacement`() {
        listOf(
            "service/station/StationLegacyExportImportService.kt",
            "service/station/StationLegacyImportApplicationService.kt",
            "manager/station/StationLegacyExportFileReader.kt",
            "convert/station/StationLegacyRowAssembler.kt"
        ).forEach { name ->
            val source = Files.readString(Path.of("src/main/kotlin/com/feedback/$name"))
            listOf("JdbcTemplate", "NamedParameterJdbcTemplate", "JpaRepository", "removeStationSources", "DELETE FROM")
                .forEach { forbidden -> assertFalse(source.contains(forbidden), "$name:$forbidden") }
            assertFalse(Regex("\\b(SELECT|INSERT|UPDATE|DELETE)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(source), name)
        }
    }

    @Test
    fun `Repository rejects an oversized batch before invoking a mapper`() {
        val repository = MyBatisStationLegacyRepositoryImpl(
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacySourceMapper::class.java),
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacyContentMapper::class.java),
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacyChannelMapper::class.java),
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacyRawPayloadMapper::class.java),
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacyIdentityMapper::class.java),
            org.mockito.Mockito.mock(com.feedback.mapper.station.legacy.StationLegacyAttachmentMapper::class.java)
        )
        assertThrows(IllegalArgumentException::class.java) {
            repository.sourcesByRecordIdsForUpdate((1..201).map(Int::toString))
        }
    }
}
