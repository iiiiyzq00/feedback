package com.feedback.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RoleSplitMigrationTest {
    @Test fun `V228 adds technical and product roles and migrates legacy users least privilege`() {
        val path=Path.of("src/main/resources/db/migration/V228__split_phase_one_technical_and_product_roles.sql")
        val sql=Files.readString(path)
        assertTrue(sql.contains("'PHASE_ONE_TECHNICAL','access:phase_one_raw_content'"))
        assertTrue(sql.contains("'PHASE_ONE_PRODUCT','access:phase_one_workspace'"))
        assertTrue(sql.contains("SELECT user_id,'PHASE_ONE_PRODUCT'"))
        assertTrue(sql.contains("DELETE FROM t_auth_user_role WHERE role_code='PHASE_ONE_USER'"))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec"))
    }
}
