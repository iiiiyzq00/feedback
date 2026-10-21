package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import com.feedback.service.impl.station.StationOnlineFinalizationServiceImpl
import com.feedback.manager.station.StationOnlineMaskingRecoveryTransaction
import com.feedback.service.impl.station.StationOnlineRetryServiceImpl
import com.feedback.model.station.StationRetryRequest
import java.nio.file.Files
import java.nio.file.Path

class StationOnlineMyBatisContractTest {
    private val mapperTables = mapOf(
        "StationOnlineSourceMapper.xml" to "t_feedback_source",
        "StationOnlineContentMapper.xml" to "t_feedback_content",
        "StationOnlineChannelMapper.xml" to "t_channel_station_feedback",
        "StationOnlineRawPayloadMapper.xml" to "t_station_feedback_raw",
        "StationOnlineIdentityMapper.xml" to "t_feedback_identity",
        "StationOnlineAttachmentMapper.xml" to "t_feedback_attachment",
        "StationDualWriteTaskMapper.xml" to "t_station_dual_write_task"
    )

    @Test
    fun `Station Online mapper XML parses and every statement remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mapperTables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/station/online/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val referencedTables = Regex("\\bt_[a-z0-9_]+\\b", RegexOption.IGNORE_CASE)
                .findAll(xml).map { it.value.lowercase() }.toSet()
            assertTrue(referencedTables == setOf(expectedTable), "$name:$referencedTables")
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.station.online.StationOnlineChannelMapper.claim"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.station.online.StationOnlineChannelMapper.resetRetryable"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.station.online.StationDualWriteTaskMapper.markFailure"))
    }

    @Test
    fun `Station Online write services contain no direct SQL JDBC JPA or database maps`() {
        listOf(
            "impl/station/StationFeedbackIngestServiceImpl.kt",
            "../manager/station/StationFeedbackWorker.kt",
            "impl/station/StationDualWriteTaskServiceImpl.kt",
            "impl/station/StationOnlineFinalizationServiceImpl.kt",
            "impl/station/StationOnlineIdentityServiceImpl.kt",
            "impl/station/StationOnlineMaskingRecoveryServiceImpl.kt",
            "impl/station/StationOnlineRetryServiceImpl.kt",
            "impl/station/StationRawPayloadServiceImpl.kt"
        ).forEach { name ->
            val source = Files.readString(Path.of("src/main/kotlin/com/feedback/service/$name"))
            listOf("JdbcTemplate", "NamedParameterJdbcTemplate", "JpaRepository", "Map<String, Any?>")
                .forEach { forbidden -> assertFalse(source.contains(forbidden), "$name:$forbidden") }
            assertFalse(Regex("\\b(SELECT|INSERT|UPDATE|DELETE)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(source), name)
        }
    }

    @Test
    fun `formal Station writes expose explicit authoritative transaction boundaries`() {
        listOf(
            StationOnlineFinalizationServiceImpl::class.java.getMethod(
                "finalizeSuccess", Long::class.javaPrimitiveType, String::class.java,
                com.feedback.model.station.ResolvedStationPhone::class.java, Boolean::class.javaPrimitiveType,
                List::class.java, Int::class.javaPrimitiveType
            ),
            StationOnlineFinalizationServiceImpl::class.java.getMethod(
                "finalizeFailure", Long::class.javaPrimitiveType, String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                String::class.java
            ),
            StationOnlineMaskingRecoveryTransaction::class.java.getMethod(
                "markRecovered", com.feedback.model.po.station.online.StationOnlineContentPo::class.java
            ),
            StationOnlineRetryServiceImpl::class.java.getMethod("retry", StationRetryRequest::class.java)
        ).forEach { method -> assertNotNull(method.getAnnotation(Transactional::class.java), method.toString()) }
    }
}
