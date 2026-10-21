package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AiPipelineMyBatisContractTest {
    @Test
    fun `AI pipeline service has no persistence escape and mapper XML stays single table`() {
        val service = Files.readString(Path.of("src/main/kotlin/com/feedback/service/ai/AiTaskPipeline.kt"))
        assertFalse(Regex("JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|nativeQuery").containsMatchIn(service))
        assertFalse(Regex("(?m)\\b(SELECT|UPDATE|INSERT|DELETE)\\s+(?:FROM|INTO|t_)").containsMatchIn(service))

        val files = listOf(
            "AiTaskMapper.xml", "FeedbackAiMapper.xml", "AiSdkContextMapper.xml",
            "AiJiraContextMapper.xml", "AiQualityContextMapper.xml", "AiRedblackContextMapper.xml"
        )
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        files.forEach { name ->
            val path = Path.of("src/main/resources/mybatis/mapper/ai/$name")
            val xml = Files.readString(path)
            assertFalse(Regex("\\b(JOIN|EXISTS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("(?i)\\b(?:FROM|UPDATE|INTO)\\s+(t_[a-z0-9_]+)")
                .findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
            assertTrue(tables.size == 1, "$name accesses $tables")
            val resource = "mybatis/mapper/ai/$name"
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
    }

    @Test
    fun `retry SQL closes exactly on incremented max retry count and terminal path clears lease`() {
        val xml = Files.readString(Path.of("src/main/resources/mybatis/mapper/ai/AiTaskMapper.xml"))
        assertTrue(xml.contains("retry_count=retry_count+1"))
        assertTrue(xml.contains("task_status=IF(retry_count&gt;=max_retries,'FAILED','RETRY_PENDING')"))
        assertTrue(xml.contains("completed_at=IF(retry_count&gt;=max_retries,CURRENT_TIMESTAMP(3),NULL)"))
        assertTrue(xml.contains("task_status='CANCELLED',next_retry_at=NULL,lease_holder=NULL"))
        // owner-only 的租约条件存在 ABA：holder 字符串复用后旧写回会被接受，因此必须叠加 generation。
        assertTrue(xml.contains("task_status='PROCESSING' AND lease_holder=#{holder} AND lease_generation=#{generation}"))
    }
}
