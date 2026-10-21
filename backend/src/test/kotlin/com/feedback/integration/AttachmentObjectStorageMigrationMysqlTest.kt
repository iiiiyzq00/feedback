package com.feedback.integration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * 专用 MySQL 前向迁移执行门禁。默认不运行，避免把不可连接的共享或开发库误当作迁移证据。
 */
@EnabledIfEnvironmentVariable(named = "ATTACHMENT_OBJECT_STORAGE_MYSQL_ENABLED", matches = "true")
class AttachmentObjectStorageMigrationMysqlTest {
    @Test
    fun `dedicated MySQL applies all pending migrations through V236`() {
        val url = requireNotNull(System.getenv("ATTACHMENT_OBJECT_STORAGE_MYSQL_URL"))
        require(Regex("/[^/?]*(?:_it|_test)(?:\\?|$)").containsMatchIn(url)) {
            "ATTACHMENT_OBJECT_STORAGE_MYSQL_DATABASE_NOT_DEDICATED"
        }
        val result = Flyway.configure()
            .dataSource(
                url,
                System.getenv("ATTACHMENT_OBJECT_STORAGE_MYSQL_USERNAME") ?: "root",
                System.getenv("ATTACHMENT_OBJECT_STORAGE_MYSQL_PASSWORD") ?: ""
            )
            .locations("classpath:db/migration")
            .target("236")
            .load()
            .migrate()

        assertTrue(result.success)
        assertTrue(result.targetSchemaVersion == "236")
    }
}
