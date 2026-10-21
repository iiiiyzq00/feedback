package com.feedback.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SdkChannelMigrationTest {
    @Test
    fun `forward migration grants SDK read scope without rewriting physical channels`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V225__include_sdk_in_phase_one_learning_machine_feedback.sql"))
        assertTrue(sql.contains("'ROLE','PHASE_ONE_USER','CHANNEL','SDK','READ','ALLOW'"))
        assertTrue(sql.contains("INSERT IGNORE"))
        assertFalse(sql.contains("UPDATE t_feedback_source"))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec/v1"))
    }
}
