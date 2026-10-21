package com.feedback.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** 专用 MySQL 迁移门禁；所有核验 SQL 仅存在于测试专用 MyBatis XML。 */
@EnabledIfEnvironmentVariable(named = "APPLICATION_IDENTITY_MYSQL_ENABLED", matches = "true")
class ApplicationIdentityMigrationMysqlTest {
    @Test
    fun `dedicated MySQL applies V237 and preserves the governed table budget`() {
        ApplicationIdentityMysqlTestContext.fromEnvironment().use { context ->
            val result = context.flyway.migrate()
            val schema = context.mapper(ApplicationIdentitySchemaTableTestMapper::class.java)
            val history = context.mapper(ApplicationIdentityFlywayHistoryTestMapper::class.java)

            assertTrue(result.success)
            assertTrue(context.flyway.info().current()?.version?.toString()?.toIntOrNull()?.let { it >= 237 } == true)
            assertEquals(73, schema.countBusinessTables(context.databaseName))
            assertEquals(
                3,
                schema.countNamedTables(
                    context.databaseName,
                    listOf(
                        "t_application_identity",
                        "t_application_identity_alias",
                        "t_feedback_primary_problem_fact"
                    )
                )
            )
            assertEquals(1, history.countSuccessfulVersion("237"))
        }
    }
}
