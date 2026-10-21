package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DerivedIndexMyBatisContractTest {
    @Test
    fun `derived index worker has no direct persistence and every mapper is single table`() {
        val service = Files.readString(Path.of("src/main/kotlin/com/feedback/service/sync/DerivedIndexSync.kt"))
        assertFalse(Regex("JdbcTemplate|NamedParameterJdbcTemplate|EntityManager|nativeQuery").containsMatchIn(service))
        assertFalse(Regex("(?m)\\b(SELECT|UPDATE|INSERT|DELETE)\\s+(?:FROM|INTO|t_)").containsMatchIn(service))
        val names = listOf("IndexAiTaskMapper.xml", "IndexFeedbackAiMapper.xml", "IndexContentMapper.xml",
            "IndexSourceMapper.xml", "IndexClusterMapper.xml")
        val config = Configuration().apply { isMapUnderscoreToCamelCase = true }
        names.forEach { name ->
            val xml = Files.readString(Path.of("src/main/resources/mybatis/mapper/sync/$name"))
            assertFalse(Regex("\\b(JOIN|EXISTS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("(?i)\\b(?:FROM|UPDATE|INTO)\\s+(t_[a-z0-9_]+)")
                .findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
            assertTrue(tables.size == 1, "$name accesses $tables")
            val resource = "mybatis/mapper/sync/$name"
            Resources.getResourceAsReader(resource).use { XMLMapperBuilder(it, config, resource, config.sqlFragments).parse() }
        }
    }

    @Test
    fun `index outbox write back is fenced by lease holder and generation`() {
        val xml = Files.readString(Path.of("src/main/resources/mybatis/mapper/sync/IndexAiTaskMapper.xml"))
        // claim 与人工重放都要推进 generation：前者发放 fencing token，后者让重放前就在飞的旧 Worker 立刻失效。
        assertTrue(Regex("lease_generation=lease_generation\\+1").findAll(xml).count() == 2)
        // complete 与 fail 都必须三重匹配；旧实现 complete 只看 status、fail 只看 id，
        // 能把已 COMPLETED 的任务改回 RETRY_PENDING，也无法识别被新租约接管的旧 Worker。
        assertTrue(Regex("task_status='PROCESSING' AND lease_holder=#\\{holder} AND lease_generation=#\\{generation}")
            .findAll(xml).count() == 2)
        assertTrue(xml.contains("lease_holder,lease_generation"))
    }
}
