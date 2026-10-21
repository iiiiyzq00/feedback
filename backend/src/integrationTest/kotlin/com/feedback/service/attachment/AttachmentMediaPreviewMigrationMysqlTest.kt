package com.feedback.service.attachment

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** 只允许专用测试库执行媒体预览迁移，避免用静态 SQL 解析冒充真实 MySQL 迁移证据。 */
@EnabledIfEnvironmentVariable(named = "ATTACHMENT_MEDIA_PREVIEW_MYSQL_ENABLED", matches = "true")
class AttachmentMediaPreviewMigrationMysqlTest {
    @Test
    fun `dedicated MySQL applies media preview migration through V240`() {
        val url = requireNotNull(System.getenv("ATTACHMENT_MEDIA_PREVIEW_MYSQL_URL"))
        require(Regex("/[^/?]*(?:_it|_test)(?:\\?|$)").containsMatchIn(url)) {
            "ATTACHMENT_MEDIA_PREVIEW_MYSQL_DATABASE_NOT_DEDICATED"
        }
        val result = Flyway.configure()
            .dataSource(
                url,
                System.getenv("ATTACHMENT_MEDIA_PREVIEW_MYSQL_USERNAME") ?: "root",
                System.getenv("ATTACHMENT_MEDIA_PREVIEW_MYSQL_PASSWORD") ?: ""
            )
            .locations("classpath:db/migration")
            .target("240")
            .load()
            .migrate()

        assertTrue(result.success)
        assertEquals("240", result.targetSchemaVersion.toString())
    }
}
