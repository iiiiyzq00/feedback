package com.feedback.mapper.applicationidentity

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 应用身份迁移、生产 Mapper 单表边界与测试 SQL 隔离的持久化契约。 */
class ApplicationIdentityPersistenceContractTest {
    private val mapperDirectory = Path.of("src/main/resources/mybatis/mapper/applicationidentity")
    private val testMapperDirectory = Path.of("src/test/resources/mybatis/mapper/applicationidentity")
    private val migration = Path.of("src/main/resources/db/migration/V237__create_application_identity_shadow_foundation.sql")

    @Test
    /** V237 只建立三个应用身份域表，不能顺手修改活动归簇。 */
    fun `P3-01 migration creates exactly three domain tables without cluster mutation`() {
        val sql = Files.readString(migration)
        assertEquals(3, Regex("(?i)CREATE\\s+TABLE").findAll(sql).count())
        assertTrue(sql.contains("t_application_identity"))
        assertTrue(sql.contains("t_application_identity_alias"))
        assertTrue(sql.contains("t_feedback_primary_problem_fact"))
        assertFalse(sql.contains("t_application_identity_command"))
        assertFalse(Regex("(?i)(ALTER|INSERT|UPDATE|DELETE)\\s+(TABLE\\s+)?t_cluster(_link)?\\b").containsMatchIn(sql))
        assertTrue(sql.contains("governed count is now 73"))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec/v1"))
    }

    @Test
    /**
     * 生产 XML 以语义清单冻结而不是脆弱文件数：第七个 SafeText Mapper 是历史只读解析所需的
     * masking PASS 单表边界，不是 identity 写边界扩张；新增/删除任何职责仍会显式失败。
     */
    fun `application identity mapper XML stays single table and parameterized`() {
        val xmlFiles = Files.list(mapperDirectory).use { stream -> stream.filter { it.toString().endsWith(".xml") }.toList() }
        val expectedFiles = setOf(
            "ApplicationIdentityAliasMapper.xml",
            "ApplicationIdentityMapper.xml",
            "ApplicationIdentityPrimaryFactMapper.xml",
            "ApplicationIdentitySafeTextMapper.xml",
            "ApplicationIdentitySdkMapper.xml",
            "ApplicationIdentitySourceMapper.xml",
            "ApplicationIdentityStationMapper.xml"
        )
        assertEquals(expectedFiles, xmlFiles.map { it.fileName.toString() }.toSet())
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        xmlFiles.forEach { path ->
            val xml = Files.readString(path)
            assertFalse(Regex("(?i)\\bJOIN\\b").containsMatchIn(xml), path.toString())
            assertFalse(Regex("(?i)\\bEXISTS\\s*\\(").containsMatchIn(xml), path.toString())
            assertFalse(xml.contains("\${"), path.toString())
            assertFalse(Regex("(?i)SELECT\\s+\\*").containsMatchIn(xml), path.toString())
            val resource = "mybatis/mapper/applicationidentity/${path.fileName}"
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        val namespace = "com.feedback.mapper.applicationidentity"
        assertTrue(configuration.hasStatement("$namespace.ApplicationIdentityMapper.compareAndIncrementVersions"))
        assertTrue(configuration.hasStatement("$namespace.ApplicationIdentityAliasMapper.lockActiveByPublicId"))
        assertTrue(configuration.hasStatement("$namespace.ApplicationIdentityAliasMapper.lockByRequestId"))
        assertTrue(configuration.hasStatement("$namespace.ApplicationIdentityPrimaryFactMapper.upsertAll"))
        assertTrue(configuration.hasStatement("$namespace.ApplicationIdentityPrimaryFactMapper.findBySourceIds"))
    }

    @Test
    /** 真实 MySQL 验证使用测试专用单表 Mapper，Kotlin 测试体禁止直接 SQL。 */
    fun `migration verification keeps SQL in single table test MyBatis XML only`() {
        val kotlinSources = listOf(
            Path.of("src/test/kotlin/com/feedback/integration/ApplicationIdentityMigrationMysqlTest.kt"),
            Path.of("src/test/kotlin/com/feedback/integration/ApplicationIdentityMysqlTestSupport.kt"),
            Path.of("src/test/kotlin/com/feedback/integration/ApplicationIdentityRebindMysqlTest.kt")
        )
        kotlinSources.forEach { path ->
            val source = Files.readString(path)
            assertFalse(source.contains("java.sql"), path.toString())
            assertFalse(source.contains("DriverManager"), path.toString())
            assertFalse(source.contains("createStatement"), path.toString())
            assertFalse(Regex("(?i)\\b(SELECT|INSERT|UPDATE|DELETE)\\b").containsMatchIn(source), path.toString())
        }
        val xmlFiles = Files.list(testMapperDirectory).use { stream ->
            stream.filter { it.toString().endsWith(".xml") }.toList()
        }
        assertEquals(4, xmlFiles.size)
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        xmlFiles.forEach { path ->
            val xml = Files.readString(path)
            assertFalse(Regex("(?i)\\bJOIN\\b").containsMatchIn(xml), path.toString())
            assertFalse(Regex("(?i)\\bEXISTS\\s*\\(").containsMatchIn(xml), path.toString())
            assertFalse(xml.contains("\${"), path.toString())
            val resource = "mybatis/mapper/applicationidentity/${path.fileName}"
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(
            configuration.hasStatement(
                "com.feedback.integration.ApplicationIdentitySchemaTableTestMapper.countBusinessTables"
            )
        )
        assertTrue(
            configuration.hasStatement(
                "com.feedback.integration.ApplicationIdentityFlywayHistoryTestMapper.countSuccessfulVersion"
            )
        )
    }
}
