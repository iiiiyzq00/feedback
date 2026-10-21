package com.feedback.service.imports

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ImportBatchGovernanceMigrationTest {
    @Test
    fun `v180 adds forward only batch governance metadata`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V180__govern_import_batch_visibility.sql"))
        listOf("governance_status","SUPERSEDED","NO_CHANGE","superseded_by_batch_id","governance_reason","governed_at")
            .forEach { assertTrue(sql.contains(it),it) }
        assertTrue(!sql.contains("DELETE FROM",ignoreCase=true))
    }
}
